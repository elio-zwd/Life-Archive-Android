package com.elio.lifearchive.feature.editor

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
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
