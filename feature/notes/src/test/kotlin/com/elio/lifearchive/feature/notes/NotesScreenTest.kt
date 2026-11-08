package com.elio.lifearchive.feature.notes

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h892dp-mdpi")
class NotesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pasteIsVisibleAndDisabledInTrashOrDuringCreation() {
        var state by mutableStateOf(NotesUiState(loading = false))
        var clicks = 0
        compose.setContent { MaterialTheme { NotesScreen(state, {}, { _, _ -> }, {}, {}, "浅色", {}, onPaste = { clicks++ }) } }
        compose.onNodeWithTag("home-paste").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { state = state.copy(query = NotesQuery(trash = true)) }
        compose.onNodeWithTag("home-paste").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(query = NotesQuery(), creating = true) }
        compose.onNodeWithTag("home-paste").assertIsNotEnabled()
    }

    private class CountingList<T>(private val entries: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size get() = entries.size
        override fun get(index: Int): T { reads++; return entries[index] }
    }

    private fun note(index: Int) = LibraryNote(
        NoteSummary(NoteId("note-$index"), LibraryPath("笔记本/笔记$index.md"), DocumentVersion("v1"), false),
        false, 0, index.toLong(), null, listOf("标签$index"), null, propertyTags = listOf("标签$index"),
    )

    @Test fun scrollingFiveHundredNotesNeverRescansDetailsAndActionsUseTheCurrentNote() {
        val notes = List(500, ::note)
        val details = CountingList(notes)
        val summaries = CountingList(notes.map { it.summary })
        var ui by mutableStateOf(NotesUiState(notes = summaries, loading = false, listing = LibraryListing(details, emptyList(), emptyList(), 0)))
        var opened: NoteId? = null
        var managed: LibraryNote? = null
        var callbackVersion by mutableIntStateOf(0)
        var openedVersion = -1
        var managedVersion = -1
        compose.setContent {
            val version = callbackVersion
            MaterialTheme { NotesScreen(ui, { opened = it; openedVersion = version }, { _, _ -> }, {}, {}, "浅色", {}, onManage = { managed = it; managedVersion = version }) }
        }
        compose.waitForIdle()
        val initialReads = details.reads to summaries.reads
        assertEquals(500, initialReads.first)
        assertEquals(500, initialReads.second)
        for (index in listOf(250, 499, 0, 250)) {
            compose.onNodeWithTag("notes-list").performScrollToKey("note:note-$index")
            compose.onNodeWithTag("note:note-$index").assertIsDisplayed()
        }
        compose.onNodeWithTag("notes-list").performTouchInput { swipeUp(durationMillis = 300) }
        compose.onNodeWithTag("notes-list").performTouchInput { swipeDown(durationMillis = 300) }
        compose.onNodeWithTag("notes-list").performScrollToKey("note:note-250")
        assertEquals(initialReads, details.reads to summaries.reads)
        compose.onNodeWithTag("note:note-250").performClick()
        assertEquals(NoteId("note-250"), opened)
        assertEquals(0, openedVersion)
        compose.runOnIdle { callbackVersion = 1; ui = ui.copy(progress = LibraryProgress("重建索引", 1, 500)) }
        compose.onNodeWithTag("note:note-250").performClick()
        assertEquals(1, openedVersion)
        compose.onNodeWithTag("note-manage:note-250").performClick()
        assertSame(notes[250], managed)
        assertEquals(1, managedVersion)
        assertEquals(initialReads, details.reads to summaries.reads)
        compose.runOnIdle { ui = ui.copy(updating = true) }
        compose.onNodeWithTag("note:note-250").assertIsNotEnabled()
        compose.onNodeWithTag("note-manage:note-250").assertIsNotEnabled()
        val changed = notes.map { if (it.summary.id == NoteId("note-250")) it.copy(pinned = true, tags = listOf("已更新"), propertyTags = listOf("已更新")) else it }
        compose.runOnIdle { ui = ui.copy(updating = false, listing = ui.listing!!.copy(notes = changed)) }
        compose.onNodeWithText("#已更新").assertIsDisplayed()
        compose.onNodeWithTag("note-manage:note-250").performClick()
        assertSame(changed[250], managed)
    }

    @Test fun statusInsertionAndReorderingPreserveTheVisibleNoteAndOffset() {
        val notes = List(500, ::note)
        var ui by mutableStateOf(NotesUiState(notes = notes.map { it.summary }, loading = false, listing = LibraryListing(notes, emptyList(), emptyList(), 0)))
        lateinit var scroll: LazyListState
        compose.setContent {
            scroll = rememberLazyListState()
            MaterialTheme { NotesScreen(ui, {}, { _, _ -> }, {}, {}, "浅色", {}, listState = scroll) }
        }
        compose.onNodeWithTag("notes-list").performScrollToKey("note:note-250")
        // contentPadding 内可能还列出上一行；锚点以 LazyListState 的首个可见索引为准。
        fun anchor() = scroll.layoutInfo.visibleItemsInfo.first { it.index == scroll.firstVisibleItemIndex }.let { it.key to scroll.firstVisibleItemScrollOffset }
        var before: Pair<Any, Int>? = null
        compose.runOnIdle { before = anchor(); ui = ui.copy(updating = true, progress = LibraryProgress("重建索引", 1, 500), error = "测试提示") }
        compose.runOnIdle { assertEquals(before, anchor()); ui = ui.copy(updating = false, progress = null, error = null) }
        compose.runOnIdle { assertEquals(before, anchor()); ui = ui.copy(notes = ui.notes.reversed(), listing = ui.listing!!.copy(notes = notes.reversed())) }
        compose.runOnIdle { assertEquals(before, anchor()) }
        compose.onNodeWithTag("note:note-250").assertIsDisplayed()
    }

    @Test fun largeTagAndFolderPickersComposeOnlyVisibleChoicesAndCanReachTheEnd() {
        val listing = LibraryListing(emptyList(), List(500) { "笔记本$it" }, List(500) { "标签$it" }, 0, topics = List(500) { "笔记本$it" })
        var ui by mutableStateOf(NotesUiState(loading = false, listing = listing))
        compose.setContent {
            MaterialTheme { NotesScreen(ui, {}, { _, _ -> }, {}, {}, "浅色", {}, onQuery = { ui = ui.copy(query = it) }) }
        }
        compose.onNodeWithTag("home-more").performClick()
        compose.onNodeWithText("全部标签").performClick()
        compose.onNodeWithText("标签499").assertDoesNotExist()
        compose.onNodeWithTag("note-filter-choices").performScrollToKey("标签499")
        compose.onNodeWithTag("tag-choice:标签499").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("note-filter-choices").assertDoesNotExist()
        compose.onNodeWithText("笔记本499").assertDoesNotExist()
        compose.onNodeWithTag("folder-filters").performScrollToKey("topic:笔记本499")
        compose.onNodeWithText("笔记本499").performClick()
        compose.runOnIdle { assertEquals(listOf("标签499"), ui.query.selectedTags); assertEquals("笔记本499", ui.query.topic) }
    }

    @Test fun visibleNoteShowsRealMetadataAndSearchClearsOnlyTheText() {
        val entry = note(1).copy(excerpt = "记录中的真实摘要", pinned = true, modified = 1_761_264_000_000L, contentTimes = com.elio.lifearchive.core.document.ContentTimes(updated = java.time.Instant.ofEpochMilli(1_761_264_000_000L).toString()))
        var ui by mutableStateOf(NotesUiState(notes = listOf(entry.summary), loading = false,
            listing = LibraryListing(listOf(entry), listOf("笔记本"), listOf("标签1"), 0),
            query = NotesQuery(text = "记录", folder = "笔记本", tag = "标签1")))
        compose.setContent {
            MaterialTheme { NotesScreen(ui, {}, { _, _ -> }, {}, {}, "浅色", {}, onQuery = { ui = ui.copy(query = it) }) }
        }
        compose.onNodeWithText("记录中的真实摘要").assertIsDisplayed()
        compose.onNodeWithText("#标签1").assertIsDisplayed()
        compose.onNodeWithTag("note-date:note-1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("note-pin:note-1", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("clear-search").assertCountEquals(1)
        compose.onNodeWithTag("clear-search").performClick()
        compose.runOnIdle {
            assertEquals("", ui.query.text)
            assertEquals("笔记本", ui.query.folder)
            assertEquals("标签1", ui.query.tag)
        }
    }

    // Robolectric 4.16.1 的 Dialog + TextField 在较宽的 dp qualifier 下可能持续请求布局。
    // https://github.com/robolectric/robolectric/issues/8460
    @Test @Config(qualifiers = "w320dp-h892dp-mdpi") fun emptyStateOffersCreationOrClearingTheCurrentFilter() {
        var ui by mutableStateOf(NotesUiState(loading = false, listing = LibraryListing(emptyList(), emptyList(), emptyList(), 0)))
        compose.setContent {
            MaterialTheme { NotesScreen(ui, {}, { _, _ -> }, {}, {}, "浅色", {}, onQuery = { ui = ui.copy(query = it) }) }
        }
        compose.onNodeWithText("从一篇笔记开始").assertIsDisplayed()
        compose.onNodeWithTag("empty-new-note").performClick()
        compose.onNodeWithTag("note-name").assertIsDisplayed()
        compose.onNodeWithTag("note-name").performTextInput("空库测试名")
        compose.onNodeWithText("空库测试名").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { ui = ui.copy(query = ui.query.copy(text = "未找到")) }
        compose.onNodeWithText("没有找到笔记").assertIsDisplayed()
        compose.onNodeWithTag("empty-clear-filters").performClick()
        compose.runOnIdle { assertEquals("", ui.query.text) }
    }

    @Test fun backupButtonUsesItsOwnAction() {
        var backups = 0
        var settings = 0
        val ui = NotesUiState(loading = false, listing = LibraryListing(emptyList(), emptyList(), emptyList(), 0))
        compose.setContent {
            MaterialTheme {
                NotesScreen(ui, {}, { _, _ -> }, {}, {}, "浅色", {}, onSettings = { settings++ }, onBackup = { backups++ })
            }
        }
        compose.onNodeWithContentDescription("本地备份").performClick()
        compose.runOnIdle { assertEquals(1, backups); assertEquals(0, settings) }
        compose.onNodeWithText("设置").performClick()
        compose.runOnIdle { assertEquals(1, backups); assertEquals(1, settings) }
    }

    @Test fun longFilterSummaryWrapsAndTheLastManagementButtonClearsTheFab() {
        val notes = List(30, ::note)
        val longFolder = "一个很长的笔记本名称".repeat(8)
        val longTag = "一个很长的标签".repeat(8)
        val ui = NotesUiState(notes = notes.map { it.summary }, loading = false,
            listing = LibraryListing(notes, listOf(longFolder), listOf(longTag), 0),
            query = NotesQuery(folder = longFolder, tag = longTag))
        compose.setContent { MaterialTheme { NotesScreen(ui, {}, { _, _ -> }, {}, {}, "浅色", {}) } }
        val summaryBounds = compose.onNodeWithTag("filter-summary").getUnclippedBoundsInRoot()
        assertTrue(summaryBounds.bottom.value - summaryBounds.top.value > 44f)
        compose.onNodeWithTag("notes-list").performScrollToKey("note:note-29")
        compose.onNodeWithTag("notes-list").performTouchInput { swipeUp() }
        val last = compose.onNodeWithTag("note-manage:note-29").assertIsDisplayed().getUnclippedBoundsInRoot()
        val fab = compose.onNodeWithTag("new-note").getUnclippedBoundsInRoot()
        assertTrue(last.bottom <= fab.top)
    }

    @Test fun thumbnailsLoadOnlyForVisibleNotesAndCancelWhenTheyLeave() {
        val notes = List(50) { index -> note(index).copy(cover = NoteCover.Attachment(LibraryPath("image-$index.png"))) }
        val requested = mutableSetOf<NoteId>()
        val cancelled = mutableSetOf<NoteId>()
        val ui = NotesUiState(notes = notes.map { it.summary }, loading = false,
            listing = LibraryListing(notes, emptyList(), emptyList(), 0))
        compose.setContent {
            MaterialTheme {
                NotesScreen(ui, {}, { _, _ -> }, {}, {}, "浅色", {}, loadThumbnail = { id, _, _ ->
                    requested += id
                    try { awaitCancellation() } finally { cancelled += id }
                })
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(NoteId("note-0") in requested); assertFalse(NoteId("note-49") in requested) }
        compose.onNodeWithTag("notes-list").performScrollToKey("note:note-49")
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(NoteId("note-49") in requested); assertTrue(NoteId("note-0") in cancelled) }
    }
}
