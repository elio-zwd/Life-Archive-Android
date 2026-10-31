package com.elio.lifearchive.data.notes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteCoverStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun open(root: File) = NotesStorage.open(context, root, StorageGate())
    private fun created(result: NoteResult<NoteSnapshot>) = (result as NoteResult.Committed).value
    private suspend fun NotesStorage.cover(id: NoteId) = (library.metadata(id) as NoteResult.Success).value.cover
    private fun image() = ByteArrayOutputStream().also { output ->
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
    }.toByteArray()

    @Test fun coverPersistsWithoutChangingBodyDraftVersionOrReadingPositionDuringSave() = runBlocking {
        val root = temp.newFolder()
        val id = open(root).use { storage ->
            val original = RawDocument("\uFEFF---\r\nunknown: 原文\r\n---\r\n# 标题\n正文")
            val note = created(storage.repository.create("独立标题.md", original))
            val id = note.summary.id
            storage.repository.updateReadingOffset(id, 17)
            storage.repository.writeDraft(Draft(id, note.summary.version, 1, RawDocument(original.text + "新输入")))
            assertEquals(NoteCover.Preset(), storage.cover(id))
            assertTrue(storage.library.setCover(id, NoteCover.Preset(CoverPreset.LAKE)) is NoteResult.Success)
            assertEquals(note.summary.version, (storage.repository.read(id) as NoteResult.Success).value.summary.version)
            assertEquals(original, (storage.repository.read(id) as NoteResult.Success).value.document)
            assertNotNull((storage.repository.readDraft(id) as NoteResult.Success).value)
            val cover = async { storage.library.setCover(id, NoteCover.Preset(CoverPreset.FOREST)) }
            val save = async { storage.repository.save(id, note.summary.version, RawDocument(original.text + "新输入"), 1) }
            assertTrue(cover.await() is NoteResult.Success); assertTrue(save.await() is NoteResult.Committed)
            assertEquals(NoteCover.Preset(CoverPreset.FOREST), storage.cover(id))
            assertEquals(17, (storage.repository.readReadingOffset(id) as NoteResult.Success).value)
            id
        }
        open(root).use { storage -> assertEquals(NoteCover.Preset(CoverPreset.FOREST), storage.cover(id)) }
    }

    @Test fun customCoverAndCandidatesSurviveRelocationTrashReopenAndBackupRestore() = runBlocking {
        val root = temp.newFolder()
        val originalBytes = image()
        val backup = temp.newFolder()
        val id = open(root).use { storage ->
            storage.repository.importAttachment("本子/图片.png", originalBytes.inputStream())
            val note = created(storage.repository.create("本子/笔记.md", RawDocument("# 标题\n![[图片.png]]\n![重复](图片.png)\n![远程](https://example.com/x.png)\n![[不存在.png]]")))
            val id = note.summary.id
            assertEquals(listOf(CoverImage(LibraryPath("本子/图片.png"), "图片.png")), (storage.library.coverImages(id) as NoteResult.Success).value)
            val cover = NoteCover.Attachment(LibraryPath("本子/图片.png"))
            assertTrue(storage.library.setCover(id, cover) is NoteResult.Success)
            assertTrue(storage.library.relocateFolder("本子", "新本子") is NoteResult.Success)
            assertEquals(NoteCover.Attachment(LibraryPath("新本子/图片.png")), storage.cover(id))
            val current = (storage.repository.read(id) as NoteResult.Success).value
            assertTrue(storage.library.trash(id, current.summary.version) is NoteResult.Success)
            storage.backupParticipant.snapshot(backup)
            assertEquals(3, JSONObject(File(backup, "state.json").readText()).getInt("format"))
            assertTrue(storage.library.restore(id, "其他/笔记.md") is NoteResult.Success)
            storage.library.setCover(id, NoteCover.Preset(CoverPreset.PAPER))
            storage.backupParticipant.restore(backup)
            assertTrue(storage.library.restore(id) is NoteResult.Success)
            id
        }
        open(root).use { storage ->
            val cover = storage.cover(id) as NoteCover.Attachment
            storage.library.openResource(cover.path).let { result ->
                assertArrayEquals(originalBytes, (result as NoteResult.Success).value.stream.use { it.readBytes() })
            }
        }
    }

    @Test fun invalidImagesAndStorageFailureKeepThePreviousCover() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val note = created(storage.repository.create("原文.md", RawDocument("正文")))
            val id = note.summary.id
            storage.library.setCover(id, NoteCover.Preset(CoverPreset.LAKE))
            storage.repository.importAttachment("损坏.png", byteArrayOf(1, 2, 3).inputStream())
            for (path in listOf("不存在.png", "损坏.png")) {
                assertTrue(storage.library.setCover(id, NoteCover.Attachment(LibraryPath(path))) is NoteResult.StorageFailure)
                assertEquals(NoteCover.Preset(CoverPreset.LAKE), storage.cover(id))
            }
            storage.fault = { if (it == "cover-write") throw IOException("磁盘不可写") }
            assertTrue(storage.library.setCover(id, NoteCover.Preset(CoverPreset.PAPER)) is NoteResult.StorageFailure)
            assertEquals(NoteCover.Preset(CoverPreset.LAKE), storage.cover(id))
            assertEquals(note.summary.version, (storage.repository.read(id) as NoteResult.Success).value.summary.version)
        }
    }

    @Test fun missingCustomFileKeepsItsSelectionForDisplayFallbackAndBackup() = runBlocking {
        val root = temp.newFolder()
        open(root).use { storage ->
            val note = created(storage.repository.create("笔记.md", RawDocument("正文")))
            storage.repository.importAttachment("封面.png", image().inputStream())
            val cover = NoteCover.Attachment(LibraryPath("封面.png"))
            storage.library.setCover(note.summary.id, cover)
            assertTrue(File(root, "attachments/封面.png").delete())
            assertEquals(cover, storage.cover(note.summary.id))
            val snapshot = temp.newFolder()
            storage.backupParticipant.snapshot(snapshot)
            storage.backupParticipant.restore(snapshot)
            assertEquals(cover, storage.cover(note.summary.id))
        }
    }

    @Test fun versionTwoDatabaseAndBackupsWithoutCoverFieldsUseTheDefault() = runBlocking {
        val root = temp.newFolder(); val id = UUID.randomUUID().toString(); val version = UUID.randomUUID().toString()
        val raw = "# 旧笔记\n正文"
        File(root, "documents").mkdirs(); File(root, "documents/旧笔记.md").writeText(raw)
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        SQLiteDatabase.openOrCreateDatabase(File(root, "state.db"), null).use { db ->
            db.execSQL("CREATE TABLE notes (id TEXT NOT NULL PRIMARY KEY,path TEXT NOT NULL,pathKey TEXT NOT NULL,version TEXT NOT NULL,hash TEXT NOT NULL,indexPending INTEGER NOT NULL,pinned INTEGER NOT NULL,readingOffset INTEGER NOT NULL,created INTEGER NOT NULL,modified INTEGER NOT NULL,deleted INTEGER)")
            db.execSQL("CREATE UNIQUE INDEX index_notes_pathKey ON notes(pathKey)")
            db.execSQL("CREATE TABLE folders (path TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42,'a8a1df25eeaedab51f0ca10ab182f6cd')")
            db.execSQL("INSERT INTO notes VALUES(?,?,?,?,?,?,?,?,?,?,NULL)", arrayOf<Any>(id, "旧笔记.md", "旧笔记.md", version, hash, 1, 1, 7, 1L, 2L))
            db.version = 2
        }
        open(root).use { storage ->
            assertTrue(storage.repository.initialize() is NoteResult.Success)
            assertEquals(NoteCover.Preset(), storage.cover(NoteId(id)))
            assertEquals(version, (storage.repository.read(NoteId(id)) as NoteResult.Success).value.summary.version.value)
            val snapshot = temp.newFolder(); storage.backupParticipant.snapshot(snapshot)
            val state = File(snapshot, "state.json"); val json = JSONObject(state.readText())
            json.put("format", 2)
            json.getJSONArray("notes").getJSONObject(0).apply { remove("coverPreset"); remove("coverAttachment") }
            state.writeText(json.toString())
            storage.library.setCover(NoteId(id), NoteCover.Preset(CoverPreset.PAPER))
            storage.backupParticipant.restore(snapshot)
            assertEquals(NoteCover.Preset(), storage.cover(NoteId(id)))
        }
    }
}
