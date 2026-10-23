package com.elio.lifearchive.feature.editor

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorInsertionTest {
    private suspend fun TestScope.commandFinished(model: EditorViewModel) {
        runCurrent()
        model.state.first { !it.commandRunning }
    }
    private suspend fun stop(model: EditorViewModel) { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin(); Dispatchers.resetMain() }

    @Test fun cancelledAndStaleExternalRequestsKeepTextAndReanchoredInsertionUndoesOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes(); val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            runCurrent(); val editor = model.session!!
            editor.text.edit { selection = TextRange(0, length) }
            model.beginInsertion(EditorInsertKind.EXTERNAL_LINK)
            assertEquals("原文", model.state.value.insertion!!.label)
            model.dismissInsertion(); assertEquals("原文", editor.text.text.toString())
            model.beginInsertion(EditorInsertKind.EXTERNAL_LINK)
            model.insertExternal("原文", "javascript:alert(1)")
            assertNotNull(model.state.value.insertionError)
            editor.insert("输入"); editor.undo()
            model.insertExternal("显示文字", "https://example.com/a(b)"); commandFinished(model)
            assertEquals("原文", editor.text.text.toString()); assertNotNull(model.state.value.insertionError)
            model.reanchorInsertion(); model.insertExternal("显示文字", "https://example.com/a(b)"); commandFinished(model)
            assertEquals("[显示文字](https://example.com/a%28b%29)", editor.text.text.toString())
            assertNull(model.state.value.insertion)
            editor.undo(); assertEquals("原文", editor.text.text.toString())
        } finally { stop(model) }
    }

    @Test fun oldQueryFailureDoesNotReplaceTheNewChoices() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes(); val old = CompletableDeferred<NoteResult<LibraryListing>>(); val fresh = CompletableDeferred<NoteResult<LibraryListing>>()
        val target = repo.note.summary.copy(id = NoteId("target"), path = LibraryPath("目录/中文.md"))
        val library = ToolbarLibrary().apply { querying = { when (it.text) { "旧" -> old.await(); "中文" -> fresh.await(); else -> listing() } } }
        val model = EditorViewModel(repo, repo.note.summary.id, true, library = library)
        try {
            runCurrent(); model.beginInsertion(EditorInsertKind.WIKI_LINK); runCurrent()
            model.searchLinkNotes("旧"); runCurrent(); model.searchLinkNotes("中文"); runCurrent()
            fresh.complete(listing(target)); runCurrent()
            assertEquals("目录/中文.md", model.state.value.linkChoices.single().path)
            old.complete(NoteResult.StorageFailure("迟到的旧查询")); runCurrent()
            assertNull(model.state.value.insertionError); assertFalse(model.state.value.linkLoading)
            assertEquals("目录/中文.md", model.state.value.linkChoices.single().path)
            model.dismissInsertion(); assertTrue(model.state.value.linkChoices.isEmpty())
        } finally { stop(model) }
    }

    @Test fun chosenIdentityIsReadAgainAndGeneratedLinkIsVerifiedBeforeInsertion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val target = NoteSnapshot(NoteSummary(NoteId("target"), LibraryPath("新目录/新名字.md"), DocumentVersion("v2"), false), RawDocument("目标"))
        val repo = object : MemoryNotes() { override suspend fun read(id: NoteId) = NoteResult.Success(if (id == target.summary.id) target else note) }
        val library = ToolbarLibrary().apply { resolving = { _, value, syntax ->
            assertEquals("新目录/新名字.md", value); assertEquals(LinkSyntax.WIKI, syntax)
            NoteResult.Success(ResolvedLink(note = target.summary.id))
        } }
        val model = EditorViewModel(repo, repo.note.summary.id, true, library = library)
        try {
            runCurrent(); model.beginInsertion(EditorInsertKind.WIKI_LINK)
            model.insertNoteLink(NoteLinkChoice(target.summary.id, "旧名字.md", null), "显示名")
            commandFinished(model)
            assertEquals("[[新目录/新名字.md|显示名]]原文", model.session!!.text.text.toString())
            model.session!!.undo(); assertEquals("原文", model.session!!.text.text.toString())
        } finally { stop(model) }
    }

    @Test fun unsafeWikiNameOffersMarkdownAndCancelledTargetReadCannotWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wait = CompletableDeferred<Unit>()
        val target = NoteSnapshot(NoteSummary(NoteId("target"), LibraryPath("目录/a#b(c).md"), DocumentVersion("v1"), false), RawDocument("目标"))
        var hold = false
        val repo = object : MemoryNotes() { override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> {
            if (id == target.summary.id) { if (hold) wait.await(); return NoteResult.Success(target) }
            return super.read(id)
        } }
        val library = ToolbarLibrary().apply { resolving = { _, value, syntax ->
            assertEquals("%E7%9B%AE%E5%BD%95/a%23b%28c%29.md", value); assertEquals(LinkSyntax.MARKDOWN, syntax)
            NoteResult.Success(ResolvedLink(note = target.summary.id))
        } }
        val model = EditorViewModel(repo, repo.note.summary.id, true, library = library)
        val choice = NoteLinkChoice(target.summary.id, target.summary.path.value, null)
        try {
            runCurrent(); model.beginInsertion(EditorInsertKind.WIKI_LINK); model.insertNoteLink(choice, "目标"); runCurrent()
            assertTrue(model.state.value.insertionError!!.contains("Markdown")); assertEquals("原文", model.session!!.text.text.toString())
            model.insertNoteLink(choice, "目标", true); commandFinished(model)
            assertEquals("[目标](%E7%9B%AE%E5%BD%95/a%23b%28c%29.md)原文", model.session!!.text.text.toString())
            model.session!!.undo(); hold = true
            model.beginInsertion(EditorInsertKind.WIKI_LINK); model.insertNoteLink(choice, "目标", true); runCurrent()
            model.dismissInsertion(); wait.complete(Unit); runCurrent()
            assertEquals("原文", model.session!!.text.text.toString())
        } finally { stop(model) }
    }

    @Test fun unresolvedTargetNeverInsertsAndAttachmentReturnRequiresItsOriginalAnchor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes(); val library = ToolbarLibrary()
        val model = EditorViewModel(repo, repo.note.summary.id, true, library = library)
        try {
            runCurrent(); model.beginInsertion(EditorInsertKind.WIKI_LINK)
            model.insertNoteLink(NoteLinkChoice(repo.note.summary.id, "样例.md", null), "目标"); runCurrent()
            assertNotNull(model.state.value.insertionError); assertEquals("原文", model.session!!.text.text.toString())
            model.dismissInsertion()
            assertTrue(model.prepareAttachment()); model.session!!.insert("新输入")
            model.insertAttachment("附件/a#b.png"); runCurrent()
            assertEquals("新输入原文", model.session!!.text.text.toString())
            assertEquals("附件/a#b.png", model.state.value.attachmentToInsert)
            model.reinsertAttachment(); commandFinished(model)
            assertEquals("新输入![a#b.png](%E9%99%84%E4%BB%B6/a%23b.png)原文", model.session!!.text.text.toString())
            assertNull(model.state.value.attachmentToInsert)
            model.session!!.undo(); assertEquals("新输入原文", model.session!!.text.text.toString())
            assertTrue(model.prepareAttachment()); model.cancelAttachment()
            assertEquals("新输入原文", model.session!!.text.text.toString())
        } finally { stop(model) }
    }

    @Test fun formatSaveTimersRunWhileHighlightIsBlockedAndProcessReopenDoesNotReplayDialog() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes(); val parserWait = CompletableDeferred<Unit>()
        val model = EditorViewModel(repo, repo.note.summary.id, true, parse = { parserWait.await(); MarkdownParser().parse(it) })
        try {
            runCurrent(); model.command(MarkdownCommand.Heading(1)); commandFinished(model)
            Snapshot.sendApplyNotifications(); runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals("# 原文", repo.draft!!.document.text)
            advanceTimeBy(500); runCurrent(); assertEquals("# 原文", repo.note.document.text)
            model.beginInsertion(EditorInsertKind.EXTERNAL_LINK); model.editInsertion { it.copy(target = "https://example.com") }
            model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            val reopened = EditorViewModel(repo, repo.note.summary.id, true)
            try { runCurrent(); assertNull(reopened.state.value.insertion); assertEquals("# 原文", reopened.session!!.text.text.toString()) }
            finally { reopened.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
        } finally { stop(model) }
    }

    @Test fun pendingDraftAndReadingModeRejectCommands() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MemoryNotes().apply { draft = Draft(note.summary.id, note.summary.version, 3, RawDocument("未恢复")) }
        val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            runCurrent(); model.command(MarkdownCommand.Heading(1)); model.beginInsertion(EditorInsertKind.CALLOUT)
            assertFalse(model.prepareAttachment()); runCurrent()
            assertNull(model.state.value.insertion); assertEquals("原文", model.session!!.text.text.toString())
            model.restoreDraft(); model.changeMode(EditorMode.READ); model.command(MarkdownCommand.Heading(1)); runCurrent()
            assertEquals("未恢复", model.session!!.text.text.toString())
        } finally { stop(model) }
    }
}

private fun listing(vararg notes: NoteSummary): NoteResult<LibraryListing> = NoteResult.Success(LibraryListing(notes.map { LibraryNote(it, false, 0, 0, null, emptyList(), null) }, emptyList(), emptyList(), 0))

private class ToolbarLibrary : LibraryRepository {
    var querying: suspend (NotesQuery) -> NoteResult<LibraryListing> = { listing() }
    var resolving: suspend (NoteId, String, LinkSyntax) -> NoteResult<ResolvedLink> = { _, _, _ -> NoteResult.NotFound }
    override suspend fun query(query: NotesQuery) = querying(query)
    override suspend fun resolve(from: NoteId, target: String, syntax: LinkSyntax) = resolving(from, target, syntax)
    override suspend fun createFolder(path: String): NoteResult<Unit> = error("unused")
    override suspend fun relocateFolder(from: String, to: String): NoteResult<Unit> = error("unused")
    override suspend fun trashFolder(path: String): NoteResult<Unit> = error("unused")
    override suspend fun setPinned(id: NoteId, pinned: Boolean): NoteResult<Unit> = error("unused")
    override suspend fun setTags(id: NoteId, expected: DocumentVersion, tags: List<String>): NoteResult<Unit> = error("unused")
    override suspend fun relocate(id: NoteId, expected: DocumentVersion, path: String): NoteResult<Unit> = error("unused")
    override suspend fun trash(id: NoteId, expected: DocumentVersion): NoteResult<Unit> = error("unused")
    override suspend fun restore(id: NoteId, path: String?): NoteResult<Unit> = error("unused")
    override suspend fun purge(id: NoteId): NoteResult<Unit> = error("unused")
    override suspend fun openResource(path: LibraryPath, offset: Long, length: Long?): NoteResult<ResourceStream> = error("unused")
    override suspend fun prepareImport(entries: List<ImportEntry>, folder: String): NoteResult<PreparedImport> = error("unused")
    override suspend fun commitImport(prepared: PreparedImport): NoteResult<Unit> = error("unused")
    override suspend fun discardImport(prepared: PreparedImport) = error("unused")
    override suspend fun export(output: OutputStream, ids: Set<NoteId>?): NoteResult<Unit> = error("unused")
}
