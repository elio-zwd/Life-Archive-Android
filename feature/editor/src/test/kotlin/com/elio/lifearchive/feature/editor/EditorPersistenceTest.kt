package com.elio.lifearchive.feature.editor

import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

internal open class MemoryNotes : NotesRepository {
    var note = NoteSnapshot(NoteSummary(NoteId("id"), LibraryPath("样例.md"), DocumentVersion("v0"), false), RawDocument("原文"))
    var draft: Draft? = null
    var failSave = false
    var failDraft = false
    var saveWait: CompletableDeferred<Unit>? = null
    var draftWait: CompletableDeferred<Unit>? = null
    var saves = 0
    var offset = 0
    override fun observeNotes() = flowOf(listOf(note.summary))
    override suspend fun initialize() = NoteResult.Success(Unit)
    override suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot> = NoteResult.Committed(note.copy(document = document), false)
    override suspend fun read(id: NoteId) = NoteResult.Success(note)
    override suspend fun readDraft(id: NoteId) = NoteResult.Success(draft)
    override suspend fun writeDraft(draft: Draft): NoteResult<Unit> {
        draftWait?.await()
        if (failDraft) return NoteResult.StorageFailure("草稿磁盘失败")
        if (this.draft?.revision?.let { it >= draft.revision } == true) return NoteResult.DraftConflict
        this.draft = draft
        return NoteResult.Success(Unit)
    }
    override suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long?): NoteResult<NoteSnapshot> {
        saves++
        saveWait?.await()
        if (failSave) return NoteResult.StorageFailure("正文磁盘失败")
        if (expected != note.summary.version) return NoteResult.Conflict(note.summary.version)
        note = note.copy(summary = note.summary.copy(version = DocumentVersion("v$saves")), document = document)
        if (draft?.revision == acknowledgedDraftRevision && draft?.document == document) draft = null
        return NoteResult.Committed(note, false)
    }
    override suspend fun readReadingOffset(id: NoteId) = NoteResult.Success(offset)
    override suspend fun updateReadingOffset(id: NoteId, offset: Int): NoteResult<Unit> { this.offset = offset; return NoteResult.Success(Unit) }
    override suspend fun rebuildIndex() = NoteResult.Success(Unit)
    override suspend fun importAttachment(path: String, input: InputStream): NoteResult<LibraryPath> = NoteResult.Success(LibraryPath(path))
    override suspend fun copyAttachment(path: LibraryPath, output: OutputStream): NoteResult<Unit> = NoteResult.NotFound
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorPersistenceTest {
    @Test fun draftAt300msAndBodyAt800ms() = runTest {
        val repo = MemoryNotes()
        val save = EditorPersistence(repo, repo.note, backgroundScope)
        save.changed("新的中文输入")
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals("新的中文输入", repo.draft!!.document.text)
        assertEquals("原文", repo.note.document.text)
        advanceTimeBy(500); runCurrent()
        assertEquals("新的中文输入", repo.note.document.text)
        assertEquals("已保存", save.status.value.label)
        assertNull(repo.draft)
    }

    @Test fun lateReceiptDoesNotClaimNewerInputWasSavedAndRebasesDraft() = runTest {
        val repo = MemoryNotes().apply { saveWait = CompletableDeferred() }
        val save = EditorPersistence(repo, repo.note, backgroundScope)
        save.changed("第一版")
        val committing = async { save.flush() }
        runCurrent()
        save.changed("第一版后又输入中文")
        repo.saveWait!!.complete(Unit)
        committing.await()
        assertEquals("第一版", repo.note.document.text)
        assertEquals("未保存", save.status.value.label)
        assertEquals("第一版后又输入中文", repo.draft!!.document.text)
        assertEquals(repo.note.summary.version, repo.draft!!.baseVersion)
        assertTrue(save.status.value.draftProtected)
        assertTrue(save.flush())
        assertEquals("第一版后又输入中文", repo.note.document.text)
    }

    @Test fun bodyFailureKeepsDraftAndRetryCommitsIt() = runTest {
        val repo = MemoryNotes().apply { failSave = true }
        val save = EditorPersistence(repo, repo.note, backgroundScope)
        save.changed("未保存内容")
        assertFalse(save.flush())
        assertTrue(save.status.value.draftProtected)
        assertEquals("未保存内容", repo.draft!!.document.text)
        assertEquals("原文", repo.note.document.text)
        repo.failSave = false
        assertTrue(save.flush())
        assertNull(repo.draft)
    }

    @Test fun inputDuringDraftWriteDoesNotLeaveAnUnacknowledgedOldDraft() = runTest {
        val repo = MemoryNotes().apply { draftWait = CompletableDeferred() }
        val save = EditorPersistence(repo, repo.note, backgroundScope)
        save.changed("写草稿时的快照")
        val committing = async { save.flush() }
        runCurrent()
        save.changed("等待磁盘期间又输入")
        repo.draftWait!!.complete(Unit)
        committing.await()
        assertEquals("写草稿时的快照", repo.note.document.text)
        assertEquals("等待磁盘期间又输入", repo.draft!!.document.text)
        assertTrue(save.flush())
        assertNull(repo.draft)
    }

    @Test fun bothWritesFailNeverReportsProtectedOrSaved() = runTest {
        val repo = MemoryNotes().apply { failDraft = true; failSave = true }
        val save = EditorPersistence(repo, repo.note, backgroundScope)
        save.changed("不能丢失")
        assertFalse(save.flush())
        assertFalse(save.status.value.draftProtected)
        assertEquals("保存失败", save.status.value.label)
    }

    @Test fun undoBackToCommittedTextDoesNotBlockLeavingOrWriteUnneededBody() = runTest {
        val repo = MemoryNotes()
        val save = EditorPersistence(repo, repo.note, backgroundScope)
        save.changed("临时改动")
        save.changed("原文")
        assertTrue(save.flush())
        assertTrue(save.status.value.draftProtected)
        assertEquals("已保存", save.status.value.label)
        assertEquals(0, repo.saves)
    }

    @Test fun versionConflictPreservesBothAndStopsAutomaticOverwrite() = runTest {
        val repo = MemoryNotes()
        val save = EditorPersistence(repo, repo.note, backgroundScope)
        repo.note = repo.note.copy(summary = repo.note.summary.copy(version = DocumentVersion("other")), document = RawDocument("另一版本"))
        save.changed("本地版本")
        assertFalse(save.flush())
        assertTrue(save.status.value.conflict)
        assertEquals("另一版本", repo.note.document.text)
        assertEquals("本地版本", repo.draft!!.document.text)
        assertFalse(save.flush())
        assertEquals(1, repo.saves)
    }

    @Test fun restoredDraftRevisionContinuesAndRawBytesSurvive() = runTest {
        val repo = MemoryNotes()
        val original = "\uFEFF---\r\nunknown: [a, b]\r\n---\r\n# 标题\n![[a.pdf#page=2]]\n%%保留%%"
        repo.draft = Draft(repo.note.summary.id, repo.note.summary.version, 41, RawDocument(original))
        val save = EditorPersistence(repo, repo.note, backgroundScope, 41)
        save.changed(original)
        assertTrue(save.flush())
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), repo.note.document.utf8())
        assertNull(repo.draft)
    }
}
