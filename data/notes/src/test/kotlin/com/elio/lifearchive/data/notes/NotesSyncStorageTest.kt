package com.elio.lifearchive.data.notes

import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.backup.*
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28], manifest = Config.NONE)
class NotesSyncStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun <T> NoteResult<T>.get(): T = when (this) { is NoteResult.Success -> value; is NoteResult.Committed -> value; else -> error(toString()) }
    @Test fun failedSyncWriteAndStaleVersionKeepSavedContent() = runBlocking {
        val gate = StorageGate(); val store = NotesStorage.open(ApplicationProvider.getApplicationContext(), temp.newFolder(), gate)
        BackupEngine(temp.newFolder(), gate, listOf(store.backupParticipant)).initialize()
        try {
            val note = store.repository.create("笔记.md", RawDocument("original")).get()
            val expected = store.sync.inventory().get().files.single()
            val source = store.repository.read(note.summary.id).get().document.text
            val bytes = (source + "changed").toByteArray()
            val desired = expected.copy(hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, size = bytes.size.toLong())
            store.fault = { if (it == "write") throw IOException("模拟磁盘失败") }
            assertTrue(store.sync.apply(expected, desired, bytes.inputStream()) is NoteResult.StorageFailure)
            store.fault = {}
            assertEquals(source, store.repository.read(note.summary.id).get().document.text)
            val current = store.repository.read(note.summary.id).get()
            store.repository.save(note.summary.id, current.summary.version, RawDocument(source + "newer")).get()
            assertTrue(store.sync.apply(expected, desired, bytes.inputStream()) is NoteResult.StorageFailure)
            assertTrue(store.repository.read(note.summary.id).get().document.text.endsWith("newer"))
        } finally { store.close() }
    }
    @Test fun interruptedSyncRenameRollsBackIdentityAndPath() = runBlocking {
        val gate = StorageGate(); val store = NotesStorage.open(ApplicationProvider.getApplicationContext(), temp.newFolder(), gate)
        BackupEngine(temp.newFolder(), gate, listOf(store.backupParticipant)).initialize()
        try {
            val note = store.repository.create("旧名.md", RawDocument("body")).get()
            val expected = store.sync.inventory().get().files.single()
            val source = store.repository.read(note.summary.id).get().document
            store.fault = { if (it == "batch-files") throw IOException("模拟改名中断") }
            assertTrue(store.sync.apply(expected, expected.copy(path = "新名.md"), source.utf8().inputStream()) is NoteResult.StorageFailure)
            store.fault = {}
            val current = store.repository.read(note.summary.id).get()
            assertEquals("旧名.md", current.summary.path.value); assertEquals(source, current.document)
            assertEquals(expected.key, store.sync.inventory().get().files.single().key)
        } finally { store.close() }
    }
}
