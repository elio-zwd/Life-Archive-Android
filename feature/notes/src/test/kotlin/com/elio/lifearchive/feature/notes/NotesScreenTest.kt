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
import com.elio.lifearchive.domain.notes.*
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

    private class CountingList<T>(private val entries: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size get() = entries.size
        override fun get(index: Int): T { reads++; return entries[index] }
    }

    private fun note(index: Int) = LibraryNote(
        NoteSummary(NoteId("note-$index"), LibraryPath("笔记本/笔记$index.md"), DocumentVersion("v1"), false),
        false, 0, index.toLong(), null, listOf("标签$index"), null,
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
        compose.onNode(hasText("管理") and hasAnyAncestor(hasTestTag("note:note-250"))).performClick()
        assertSame(notes[250], managed)
        assertEquals(1, managedVersion)
        assertEquals(initialReads, details.reads to summaries.reads)
        compose.runOnIdle { ui = ui.copy(updating = true) }
        compose.onNodeWithTag("note:note-250").assertIsNotEnabled()
        compose.onNode(hasText("管理") and hasAnyAncestor(hasTestTag("note:note-250"))).assertIsNotEnabled()
        val changed = notes.map { if (it.summary.id == NoteId("note-250")) it.copy(pinned = true, tags = listOf("已更新")) else it }
        compose.runOnIdle { ui = ui.copy(updating = false, listing = ui.listing!!.copy(notes = changed)) }
        compose.onNodeWithText("#已更新").assertIsDisplayed()
        compose.onNodeWithText("已置顶 · 管理").performClick()
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
        val listing = LibraryListing(emptyList(), List(500) { "笔记本$it" }, List(500) { "标签$it" }, 0)
        var ui by mutableStateOf(NotesUiState(loading = false, listing = listing))
        compose.setContent {
            MaterialTheme { NotesScreen(ui, {}, { _, _ -> }, {}, {}, "浅色", {}, onQuery = { ui = ui.copy(query = it) }) }
        }
        for ((label, target) in listOf("全部标签" to "标签499", "全部笔记本" to "笔记本499")) {
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(target).assertDoesNotExist()
            compose.onNodeWithTag("note-filter-choices").performScrollToKey(target)
            compose.onNodeWithText(target).performClick()
            compose.onNodeWithTag("note-filter-choices").assertDoesNotExist()
            compose.onNodeWithText(target).assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals("标签499", ui.query.tag); assertEquals("笔记本499", ui.query.folder) }
    }
}
