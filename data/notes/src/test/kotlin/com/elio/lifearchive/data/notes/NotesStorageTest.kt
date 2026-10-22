package com.elio.lifearchive.data.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.domain.notes.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
@Config(sdk = [28])
class NotesStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun open(root: File) = NotesStorage.open(context, root, StorageGate())
    private fun created(result: NoteResult<NoteSnapshot>) = (result as NoteResult.Committed).value
    private class Crash : Error()

    @Test fun realFilesPreserveContentAcrossReopenAndRejectStaleSave() = runBlocking {
        val root = temp.newFolder()
        val original = RawDocument("\uFEFF---\r\nunknown: 原文\r\n---\r\n# 中文\n![[file.pdf]]")
        val first = open(root).use { storage ->
            val note = created(storage.repository.create("日记/记录.md", original))
            assertArrayEquals(original.utf8(), File(root, "documents/日记/记录.md").readBytes())
            note
        }
        open(root).use { storage ->
            val repo = storage.repository
            assertEquals(original, (repo.read(first.summary.id) as NoteResult.Success).value.document)
            val outcomes = listOf("甲", "乙").map { text -> async { repo.save(first.summary.id, first.summary.version, RawDocument(text)) } }.awaitAll()
            assertEquals(1, outcomes.count { it is NoteResult.Committed })
            assertEquals(1, outcomes.count { it is NoteResult.Conflict })
            assertEquals(first.summary.id, repo.observeNotes().first().single().id)
        }
    }

    @Test fun newDraftSurvivesOlderAcknowledgementAndWriteFailure() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val repo = storage.repository
            val note = created(repo.create("草稿.md", RawDocument("old")))
            val id = note.summary.id
            val version = note.summary.version
            repo.writeDraft(Draft(id, version, 1, RawDocument("first")))
            repo.writeDraft(Draft(id, version, 2, RawDocument("second")))
            val saved = created(repo.save(id, version, RawDocument("first"), 1))
            assertEquals(2L, (repo.readDraft(id) as NoteResult.Success).value!!.revision)
            storage.fault = { if (it == "write") throw IOException("磁盘已满") }
            assertTrue(repo.save(id, saved.summary.version, RawDocument("second"), 2) is NoteResult.StorageFailure)
            assertEquals("first", (repo.read(id) as NoteResult.Success).value.document.text)
            assertEquals("second", (repo.readDraft(id) as NoteResult.Success).value!!.document.text)
            storage.fault = {}
            repo.save(id, saved.summary.version, RawDocument("second"), 2)
            assertNull((repo.readDraft(id) as NoteResult.Success).value)
        }
    }

    @Test fun journalRecoversBeforeAndAfterFileReplacement() = runBlocking {
        for (point in listOf("journal", "file")) {
            val root = temp.newFolder()
            val note = open(root).use { storage ->
                val original = created(storage.repository.create("中断.md", RawDocument("old")))
                storage.fault = { if (it == point) throw Crash() }
                try { storage.repository.save(original.summary.id, original.summary.version, RawDocument("new")); fail("应中断") } catch (_: Crash) { }
                original
            }
            open(root).use { storage ->
                val recovered = (storage.repository.read(note.summary.id) as NoteResult.Success).value
                assertEquals("new", recovered.document.text)
                assertNotEquals(note.summary.version, recovered.summary.version)
                assertFalse(File(root, "operations/save.json").exists())
            }
        }
    }

    @Test fun indexFailureDoesNotLoseCommittedBodyOrIdentity() = runBlocking {
        open(temp.newFolder()).use { storage ->
            storage.fault = { if (it == "index") throw IOException("索引故障") }
            val result = storage.repository.create("搜索.md", RawDocument("中文 searchable")) as NoteResult.Committed
            assertTrue(result.indexPending)
            assertEquals("中文 searchable", (storage.repository.read(result.value.summary.id) as NoteResult.Success).value.document.text)
            storage.fault = {}
            assertTrue(storage.repository.rebuildIndex() is NoteResult.Success)
            assertEquals(result.value.summary.id, storage.repository.observeNotes().first().single().id)
            assertFalse(storage.repository.observeNotes().first().single().indexPending)
        }
    }

    @Test fun pathValidationAndAttachmentReopen() = runBlocking {
        val root = temp.newFolder()
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val path = open(root).use { storage ->
            val repo = storage.repository
            assertTrue(repo.create("../bad.md", RawDocument("")) is NoteResult.InvalidPath)
            assertTrue(repo.create("CON.md", RawDocument("")) is NoteResult.InvalidPath)
            repo.create("中文.md", RawDocument(""))
            assertTrue(repo.create("中文.md", RawDocument("different")) is NoteResult.PathConflict)
            repo.create("Test.md", RawDocument(""))
            assertTrue(repo.create("test.md", RawDocument("")) is NoteResult.PathConflict)
            (repo.importAttachment("图片/原图.bin", ByteArrayInputStream(bytes)) as NoteResult.Success).value
        }
        open(root).use { storage ->
            val output = ByteArrayOutputStream()
            assertTrue(storage.repository.copyAttachment(path, output) is NoteResult.Success)
            assertArrayEquals(bytes, output.toByteArray())
        }
    }

    @Test fun readingOffsetSurvivesReopenWithoutChangingBodyOrVersion() = runBlocking {
        val root = temp.newFolder()
        val note = open(root).use { storage ->
            val note = created(storage.repository.create("阅读位置.md", RawDocument("# 原文\n段落")))
            assertTrue(storage.repository.updateReadingOffset(note.summary.id, 5) is NoteResult.Success)
            assertTrue(storage.repository.updateReadingOffset(note.summary.id, -1) is NoteResult.InvalidPosition)
            note
        }
        open(root).use { storage ->
            assertEquals(5, (storage.repository.readReadingOffset(note.summary.id) as NoteResult.Success).value)
            assertEquals(note, (storage.repository.read(note.summary.id) as NoteResult.Success).value)
        }
    }

    @Test fun damagedIndexIsRebuiltWithoutResettingState() = runBlocking {
        val root = temp.newFolder()
        val note = open(root).use { created(it.repository.create("保留ID.md", RawDocument("正文"))) }
        File(root, "index.db").writeText("故意破坏派生索引")
        open(root).use { storage ->
            assertTrue(storage.repository.rebuildIndex() is NoteResult.Success)
            assertEquals(note.summary.id, storage.repository.observeNotes().first().single().id)
            assertEquals("正文", (storage.repository.read(note.summary.id) as NoteResult.Success).value.document.text)
        }
    }

    @Test fun damagedStateIsNotSilentlyDeletedAndRecreated() = runBlocking {
        val root = temp.newFolder()
        open(root).use { created(it.repository.create("不能丢ID.md", RawDocument("原文"))) }
        val stateFile = File(root, "state.db")
        val damaged = "损坏状态库，不能删库冒充成功".toByteArray()
        stateFile.writeBytes(damaged)
        open(root).use { storage ->
            assertTrue(storage.repository.initialize() is NoteResult.StorageFailure)
            assertArrayEquals(damaged, stateFile.readBytes())
            assertEquals("原文", File(root, "documents/不能丢ID.md").readText())
        }
    }

    @Test fun fiveHundredNotesAndOneMiBRemainReadable() = runBlocking {
        val root = temp.newFolder()
        open(root).use { storage ->
            repeat(500) { i ->
                val text = if (i == 499) "x".repeat(1024 * 1024) else "中英 sample $i\n".repeat(600)
                val note = created(storage.repository.create("本子/笔记$i.md", RawDocument(text)))
                assertEquals(text, (storage.repository.read(note.summary.id) as NoteResult.Success).value.document.text)
            }
            assertEquals(500, storage.repository.observeNotes().first().size)
            assertEquals(499, (storage.library.query(NotesQuery(text = "中英")) as NoteResult.Success).value.notes.size)
            val times = (0 until 20).map { val start = System.nanoTime(); assertEquals(1, (storage.library.query(NotesQuery(text = "sample 123")) as NoteResult.Success).value.notes.size); (System.nanoTime() - start) / 1_000_000 }
            println("电脑热搜索 20 次 p95=${times.sorted()[18]} ms；不代表目标机性能")
        }
        open(root).use { assertEquals(500, it.repository.observeNotes().first().size) }
    }
}
