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
    @Test fun sourceUpdateThrownWriteCanRetryAfterTheRealWriteLockIsReleased() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = object : MemoryNotes() {
            var throwing = true
            override suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long?): NoteResult<NoteSnapshot> {
                if (throwing) error("临时写入异常")
                return super.save(id, expected, document, acknowledgedDraftRevision)
            }
        }
        val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            runCurrent()
            model.openSourceLink { "https://example.test/v" }
            model.applySourceLink(); runCurrent()
            assertNotNull(model.state.value.sourceLink!!.error)
            assertEquals("原文", repo.note.document.text)
            assertFalse(model.persistence!!.writing)
            repo.throwing = false
            model.applySourceLink(); runCurrent()
            assertEquals("链接已更新", model.state.value.message)
            assertEquals("https://example.test/v", NoteSource.read(repo.note.document.text))
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun sourceUpdateIncludesUnsavedTextPreservesModeAndIsOneUndoStep() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes(); val model = EditorViewModel(repo, repo.note.summary.id, false)
        try {
            runCurrent()
            model.session!!.insert("未保存正文")
            val before = model.session!!.content.source
            model.openSourceLink { "分享 https://xhslink.cn/o/6va5s9Fs9CN 口令" }
            model.applySourceLink(); model.applySourceLink(); runCurrent()
            assertEquals(1, repo.saves)
            assertEquals(EditorMode.READ, model.state.value.mode)
            assertEquals("https://xhslink.cn/o/6va5s9Fs9CN", NoteSource.read(repo.note.document.text))
            assertTrue(repo.note.document.text.endsWith(before))
            assertEquals("链接已更新", model.state.value.message)
            assertNull(model.state.value.sourceLink)
            model.session!!.undo()
            assertEquals(before, model.session!!.content.source)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun sourceUpdateFailureKeepsInputAndOnlyReportsSuccessAfterRetryCommits() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes().apply { failSave = true }
        val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            runCurrent()
            model.openSourceLink { "https://v.douyin.com/ySydEn8zeT8/" }
            model.applySourceLink(); runCurrent()
            assertEquals("原文", repo.note.document.text)
            assertNull(model.state.value.message)
            assertNotNull(model.state.value.sourceLink!!.error)
            assertEquals("https://v.douyin.com/ySydEn8zeT8/", NoteSource.read(model.session!!.content.source))
            repo.failSave = false
            model.applySourceLink(); runCurrent()
            assertEquals("链接已更新", model.state.value.message)
            assertNull(model.state.value.sourceLink)
            assertEquals(EditorMode.SOURCE, model.state.value.mode)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun sourceUpdateConflictAndPendingDraftNeverOverwriteTheDisk() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes(); val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            runCurrent()
            model.openSourceLink { "https://example.test/v" }
            repo.note = repo.note.copy(summary = repo.note.summary.copy(version = DocumentVersion("external")), document = RawDocument("外部更新"))
            model.applySourceLink(); runCurrent()
            assertEquals("外部更新", repo.note.document.text)
            assertTrue(model.persistence!!.status.value.conflict)
            assertNotNull(model.state.value.sourceLink!!.error)
            assertNull(model.state.value.message)
        } finally { model.viewModelScope.cancel() }
        val draftRepo = MemoryNotes().apply { draft = Draft(note.summary.id, note.summary.version, 7, RawDocument("待恢复")) }
        val pending = EditorViewModel(draftRepo, draftRepo.note.summary.id, true)
        try {
            runCurrent()
            var reads = 0
            pending.openSourceLink { reads++; "https://example.test/v" }
            assertEquals(0, reads); assertNull(pending.state.value.sourceLink); assertEquals(0, draftRepo.saves)
        } finally { pending.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun modeSwitchesDuringSaveKeepTheNewestTextAndDraftVersion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes().apply { saveWait = kotlinx.coroutines.CompletableDeferred() }
        val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            runCurrent()
            model.session!!.edit { replace(0, length, "保存第一版") }
            model.changeMode(EditorMode.READ); runCurrent()
            assertEquals(1, repo.saves)
            model.changeMode(EditorMode.SOURCE)
            model.session!!.edit { append("，继续中文输入") }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); runCurrent()
            model.changeMode(EditorMode.READ); runCurrent()
            repo.saveWait!!.complete(Unit); runCurrent()
            assertTrue(model.flush())
            assertEquals("保存第一版，继续中文输入", repo.note.document.text)
            assertEquals(repo.note.document.text, model.session!!.content.source)
            assertNull(repo.draft)
            assertEquals("已保存", model.persistence!!.status.value.label)
        } finally { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun focusAndCursorChangesDoNotStartParsingAndTypingDebouncesFullParsing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes(); var parses = 0
        val model = EditorViewModel(repo, repo.note.summary.id, true, parse = { parses++; MarkdownParser().parse(it) })
        try {
            model.state.first { it.parsed != null }
            val session = model.session!!
            val initial = session.content
            repeat(20) {
                session.edit { selection = androidx.compose.ui.text.TextRange(it % (length + 1)) }
                session.requestInputFocus()
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); runCurrent()
            }
            assertSame(initial, session.content); assertEquals(1, parses)
            repeat(12) {
                session.edit { append("字") }
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); runCurrent()
            }
            val latest = session.content
            model.state.first { it.projection?.document?.source == latest.source }
            advanceTimeBy(199); runCurrent(); assertEquals(1, parses)
            advanceTimeBy(1); runCurrent()
            model.state.first { it.parsed?.source == latest.source }
            assertEquals(2, parses)
        } finally { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }
    }

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
