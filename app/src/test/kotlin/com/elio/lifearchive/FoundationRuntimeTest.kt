package com.elio.lifearchive

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.backup.BackupEngine
import com.elio.lifearchive.core.backup.BackupParticipant
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.preferences.PreferencesStore
import com.elio.lifearchive.data.notes.NotesStorage
import com.elio.lifearchive.core.preferences.ThemeMode
import com.elio.lifearchive.domain.notes.Draft
import com.elio.lifearchive.domain.notes.NoteResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FoundationRuntimeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun composedStorageBackupRestoresIdsDraftsSettingsAndAttachments() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = temp.newFolder()
        val runtime = FoundationRuntime(context, root)
        val original = (runtime.notes.create("文件名.md", RawDocument("---\nunknown: value\n---\n# 正文标题\n")) as NoteResult.Committed).value
        val id = original.summary.id
        runtime.notes.writeDraft(Draft(id, original.summary.version, 3, RawDocument("未保存草稿")))
        runtime.preferences.setTheme(ThemeMode.DARK)
        runtime.preferences.setFontScale(1.3f)
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val path = (runtime.notes.importAttachment("资料.bin", ByteArrayInputStream(bytes)) as NoteResult.Success).value
        val archive = ByteArrayOutputStream().also { runtime.backup.export(it) }.toByteArray()
        runtime.notes.save(id, original.summary.version, RawDocument("更改后的正文"))
        runtime.preferences.setTheme(ThemeMode.LIGHT)
        val prepared = runtime.backup.prepare(ByteArrayInputStream(archive))
        assertEquals(ThemeMode.LIGHT, runtime.preferences.preferences.first().theme)
        runtime.backup.commit(prepared)
        runtime.closeAndJoin()
        val reopened = FoundationRuntime(context, root)
        try {
            assertEquals(original.document, (reopened.notes.read(id) as NoteResult.Success).value.document)
            assertEquals(original.summary.version, (reopened.notes.read(id) as NoteResult.Success).value.summary.version)
            assertEquals(3L, (reopened.notes.readDraft(id) as NoteResult.Success).value!!.revision)
            assertEquals(ThemeMode.DARK, reopened.preferences.preferences.first().theme)
            assertEquals(1.3f, reopened.preferences.preferences.first().fontScale)
            val out = ByteArrayOutputStream()
            reopened.notes.copyAttachment(path, out)
            assertArrayEquals(bytes, out.toByteArray())
            assertTrue(reopened.notes.rebuildIndex() is NoteResult.Success)
            assertEquals(id, reopened.notes.observeNotes().first().single().id)
            assertFalse(File(root, "backup-work/transaction.json").exists())
        } finally { reopened.closeAndJoin() }
    }

    @Test fun fiftyMiBAttachmentStreamsThroughFileBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = FoundationRuntime(context, temp.newFolder())
        val size = 50L * 1024 * 1024
        val input = object : java.io.InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining-- > 0) 7 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                require(length <= 64 * 1024)
                val count = minOf(length.toLong(), remaining).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 7.toByte())
                remaining -= count
                return count
            }
        }
        try {
            val path = (runtime.notes.importAttachment("large.bin", input) as NoteResult.Success).value
            val archive = temp.newFile("backup.zip")
            archive.outputStream().use { runtime.backup.export(it) }
            val prepared = archive.inputStream().use { runtime.backup.prepare(it) }
            runtime.backup.commit(prepared)
            var count = 0L
            val out = object : java.io.OutputStream() {
                override fun write(value: Int) { check(value == 7); count++ }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    check(length <= 64 * 1024)
                    check((offset until offset + length).all { buffer[it] == 7.toByte() })
                    count += length
                }
            }
            assertTrue(runtime.notes.copyAttachment(path, out) is NoteResult.Success)
            assertEquals(size, count)
        } finally { runtime.closeAndJoin() }
    }

    @Test fun interruptedRealRestoreRollsBackOnNextRuntime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = temp.newFolder()
        val gate = StorageGate()
        val notes = NotesStorage.open(context, File(root, "notes"), gate)
        val settings = PreferencesStore(File(root, "settings.preferences_pb"), gate)
        var interrupt = false
        val wrapped = object : BackupParticipant by notes.backupParticipant {
            override suspend fun restore(directory: File) {
                notes.backupParticipant.restore(directory)
                if (interrupt) throw AssertionError("模拟进程退出")
            }
        }
        val engine = BackupEngine(File(root, "backup-work"), gate, listOf(wrapped, settings.backupParticipant))
        val note = (notes.repository.create("恢复.md", RawDocument("backup")) as NoteResult.Committed).value
        val archive = ByteArrayOutputStream().also { engine.export(it) }.toByteArray()
        val current = (notes.repository.save(note.summary.id, note.summary.version, RawDocument("current")) as NoteResult.Committed).value
        settings.repository.setTheme(ThemeMode.DARK)
        interrupt = true
        try { engine.commit(engine.prepare(ByteArrayInputStream(archive))); fail("应中断") } catch (_: AssertionError) { }
        assertTrue(File(root, "backup-work/transaction.json").exists())
        notes.close()
        settings.closeAndJoin()
        val reopened = FoundationRuntime(context, root)
        try {
            assertEquals(current, (reopened.notes.read(note.summary.id) as NoteResult.Success).value)
            assertEquals(ThemeMode.DARK, reopened.preferences.preferences.first().theme)
            assertFalse(File(root, "backup-work/transaction.json").exists())
        } finally { reopened.closeAndJoin() }
    }
}
