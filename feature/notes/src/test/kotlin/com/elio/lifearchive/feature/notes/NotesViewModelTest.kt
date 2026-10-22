package com.elio.lifearchive.feature.notes

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private class FakeNotes : NotesRepository {
        var creates = 0
        var result: NoteResult<NoteSnapshot> = NoteResult.PathConflict
        override fun observeNotes() = flowOf(emptyList<NoteSummary>())
        override suspend fun initialize() = NoteResult.Success(Unit)
        override suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot> { creates++; return result }
        override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> = error("unused")
        override suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long?): NoteResult<NoteSnapshot> = error("unused")
        override suspend fun readDraft(id: NoteId): NoteResult<Draft?> = error("unused")
        override suspend fun writeDraft(draft: Draft): NoteResult<Unit> = error("unused")
        override suspend fun readReadingOffset(id: NoteId): NoteResult<Int> = error("unused")
        override suspend fun updateReadingOffset(id: NoteId, offset: Int): NoteResult<Unit> = error("unused")
        override suspend fun rebuildIndex(): NoteResult<Unit> = error("unused")
        override suspend fun importAttachment(path: String, input: InputStream): NoteResult<LibraryPath> = error("unused")
        override suspend fun copyAttachment(path: LibraryPath, output: OutputStream): NoteResult<Unit> = error("unused")
    }

    @Test fun noCreateUntilConfirmationAndFailuresDoNotNavigate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeNotes()
        val model = NotesViewModel(repo)
        try {
            runCurrent()
            assertEquals(0, repo.creates)
            var navigated = false
            for (name in listOf("", "非法/路径", "CON", "尾随点.", "尾随空格 ", "a:b")) model.create(name) { navigated = true }
            assertEquals(0, repo.creates)
            model.create("重名") { navigated = true }
            runCurrent()
            assertFalse(navigated)
            assertTrue(model.state.value.error!!.contains("同名"))
            val note = NoteSnapshot(NoteSummary(NoteId("new"), LibraryPath("新笔记.md"), DocumentVersion("v1"), false), RawDocument(""))
            repo.result = NoteResult.Committed(note, false)
            model.create("新笔记") { navigated = true }
            runCurrent()
            assertTrue(navigated)
            assertNull(model.state.value.error)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
