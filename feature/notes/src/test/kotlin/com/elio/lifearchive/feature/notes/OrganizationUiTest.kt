package com.elio.lifearchive.feature.notes

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28], qualifiers = "w412dp-h892dp-mdpi")
class OrganizationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun note(id: String, path: String, tags: List<String>) = LibraryNote(NoteSummary(NoteId(id), LibraryPath(path), DocumentVersion("v"), false), false, 0, 0, null, tags, null, propertyTags = tags)
    @Test fun topicsCollapseAndTagGroupingNeverDuplicatesPhysicalNotes() {
        val notes = listOf(note("1", "职业发展/个人笔记/甲.md", listOf("职业发展/面试", "沟通表达/表达")), note("2", "旧目录/乙.md", emptyList()))
        val state = NotesUiState(notes = notes.map { it.summary }, listing = LibraryListing(notes, emptyList(), notes.flatMap { it.tags }, 0), loading = false)
        compose.setContent { MaterialTheme { NotesScreen(state, {}, { _, _ -> }, {}, {}, "浅色", {}) } }
        compose.onNodeWithText("▾ 待整理 · 1").performClick()
        compose.onNodeWithTag("note:2").assertDoesNotExist()
        compose.onNodeWithText("▸ 待整理 · 1").performClick()
        compose.onNodeWithTag("note:2").assertExists()
        compose.onNodeWithTag("home-more").performClick()
        compose.onNodeWithText("改为按标签分组").performClick()
        compose.onNodeWithTag("notes-list").performScrollToKey("group:职业发展/面试")
        compose.onNodeWithText("▾ 职业发展/面试 · 1（同篇可见于多组）").assertExists()
        assertEquals(2, state.notes.size)
    }
    @Test fun tagSelectionIncludesParentsAndCanSwitchBetweenAllAndAny() {
        var state by mutableStateOf(NotesUiState(loading = false, listing = LibraryListing(emptyList(), emptyList(), listOf("职业发展/面试/自我介绍"), 0)))
        compose.setContent { MaterialTheme { NotesScreen(state, {}, { _, _ -> }, {}, {}, "浅色", {}, onQuery = { state = state.copy(query = it) }) } }
        compose.onNodeWithTag("home-more").performClick(); compose.onNodeWithText("标签筛选").performClick()
        compose.onAllNodesWithText("职业发展").onLast().assertExists()
        compose.onNodeWithText("  面试").assertExists()
        compose.onAllNodes(isToggleable()).onLast().performClick()
        compose.onNodeWithText("任一符合").performClick()
        compose.onNodeWithText("完成").performClick()
        assertEquals(TagMatch.ANY, state.query.tagMatch)
        assertTrue(state.query.selectedTags.isNotEmpty())
    }
}
