package com.elio.lifearchive.feature.editor

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @Test fun readingDefersHighlightUntilFirstScreenAndEditingReusesThePreparedResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes(); var parses = 0
        val model = EditorViewModel(repo, repo.note.summary.id, false, parse = { parses++; MarkdownParser().parse(it) })
        try {
            runCurrent(); assertNotNull(model.session); assertEquals(0, parses)
            model.readerLoaded(ReaderLoadEvent(ReaderLoadPhase.FIRST_SCREEN, "reading", 25, false))
            advanceTimeBy(199); runCurrent(); assertEquals(0, parses)
            advanceTimeBy(1); runCurrent(); assertEquals(1, parses)
            model.changeMode(EditorMode.SOURCE); runCurrent(); assertEquals(1, parses)
            assertEquals("原文", model.state.value.parsed!!.source)
        } finally { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun retiredSessionCannotWriteIntoARestoredLibraryOrAcceptLateAttachmentCallbacks() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = MemoryNotes()
        val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            assertTrue(model.retire())
            repo.note = repo.note.copy(document = RawDocument("已恢复的资料"))
            val saves = repo.saves
            model.insertAttachment("迟到.png"); assertFalse(model.applyTags(listOf("迟到")))
            model.session!!.text.edit { append("过期输入") }
            model.backgroundSave(); model.readingPosition(99); assertTrue(model.flush())
            advanceUntilIdle()
            assertEquals(saves, repo.saves); assertEquals("已恢复的资料", repo.note.document.text); assertEquals(0, repo.offset)
        } finally { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun slowHighlightDoesNotDelayDraftOrBodyAndCannotPublishAnOldProjection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val model = EditorViewModel(repo, repo.note.summary.id, true, parse = { text -> if (text != "原文") release.await(); MarkdownParser().parse(text) })
        try {
            runCurrent()
            model.session!!.text.edit { replace(0, length, "回车\n\n第一版") }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); runCurrent()
            advanceTimeBy(300); runCurrent()
            assertEquals("回车\n\n第一版", repo.draft!!.document.text)
            advanceTimeBy(500); runCurrent(); assertEquals("回车\n\n第一版", repo.note.document.text)
            model.session!!.text.edit { append("\n第二版") }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); runCurrent()
            release.complete(Unit)
            val latest = model.state.first { it.projection?.document?.source == "回车\n\n第一版\n第二版" }
            assertEquals("回车\n\n第一版\n第二版", latest.projection!!.document.source)
        } finally { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun failedReadHasNoUnsavedInputAndCanReturn() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = object : MemoryNotes() { override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> = NoteResult.NotFound }
        val model = EditorViewModel(repo, repo.note.summary.id, false)
        try { var left = false; model.leave { left = true }; assertTrue(left); assertNotNull(model.state.value.error) }
        finally { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun sameVersionDraftRequiresConfirmationAndRestoresAfterNewViewModel() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = MemoryNotes().apply { draft = Draft(note.summary.id, note.summary.version, 12, RawDocument("待恢复的中文")) }
        val first = EditorViewModel(repo, repo.note.summary.id, true)
        first.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
        val reopened = EditorViewModel(repo, repo.note.summary.id, false)
        try {
            assertEquals(EditorMode.READ, reopened.state.value.mode)
            assertEquals("原文", reopened.session!!.text.text.toString())
            assertNotNull(reopened.state.value.pendingDraft)
            reopened.flush()
            assertEquals(0, repo.saves)
            reopened.restoreDraft()
            assertEquals("待恢复的中文", reopened.session!!.text.text.toString())
            assertTrue(reopened.flush())
            assertEquals("待恢复的中文", repo.note.document.text)
            assertNull(repo.draft)
        } finally { reopened.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun conflictingDraftCannotEditOrOverwriteAndCanBeCopied() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = MemoryNotes().apply { draft = Draft(note.summary.id, DocumentVersion("old"), 7, RawDocument("旧版本草稿")) }
        val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            assertTrue(model.state.value.draftConflict)
            model.changeMode(EditorMode.SOURCE)
            assertEquals(EditorMode.READ, model.state.value.mode)
            model.restoreDraft()
            model.flush()
            assertEquals(0, repo.saves)
            var copied = false
            model.copyDraft { copied = true }
            assertTrue(copied)
            assertEquals("原文", repo.note.document.text)
            assertEquals("旧版本草稿", repo.draft!!.document.text)
        } finally { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun bothWritesFailPreventsNavigationAndReadingPositionPersists() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = MemoryNotes().apply { failDraft = true; failSave = true; offset = 2 }
        val model = EditorViewModel(repo, repo.note.summary.id, false)
        try {
            assertEquals(2, model.initialReadingOffset)
            model.session!!.text.edit { replace(0, length, "新输入") }
            var left = false
            model.leave { left = true }
            assertFalse(left)
            assertEquals("新输入", model.session!!.text.text.toString())
            repo.failDraft = false; repo.failSave = false
            model.readingPosition(4)
            model.leave { left = true }
            assertTrue(left)
            assertEquals(4, repo.offset)
        } finally { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }
}
