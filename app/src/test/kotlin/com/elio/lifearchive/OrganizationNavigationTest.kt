package com.elio.lifearchive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LifeArchiveApplication::class, qualifiers = "w320dp-h960dp-mdpi")
class OrganizationNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realOrganizerPreviewSurvivesRecreationAndOnlyConfirmationMovesTheNote() {
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("home-more")).fetchSemanticsNodes().isNotEmpty() }
        val original = runBlocking { (compose.activity.notes.createManaged("历史资料.md", RawDocument("---\ndate: 2020-01-01\n---\n历史正文"), false) as NoteResult.Committed).value }
        compose.onNodeWithTag("home-more").performClick()
        compose.onNodeWithText("分类与整理", substring = false).performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("全选 1 篇")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("全选 1 篇").performClick()
        compose.onNodeWithTag("organization-topic").performTextReplacement("职业发展")
        compose.onNodeWithTag("organization-kind-PERSONAL").performClick()
        compose.onNodeWithTag("organization-preview").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("organization-apply")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(original.document, runBlocking { (compose.activity.notes.read(original.summary.id) as NoteResult.Success).value.document })
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("organization-topic").assertTextContains("职业发展")
        compose.onNodeWithTag("organization-apply").performScrollTo().performClick()
        compose.waitUntil(15000) { runBlocking { compose.activity.notes.observeNotes().first().any { it.id == original.summary.id && it.path.value == "职业发展/个人笔记/历史资料.md" } } }
        val result = runBlocking { (compose.activity.notes.read(original.summary.id) as NoteResult.Success).value }
        assertEquals(NoteIdentity.read(original.document.text), NoteIdentity.read(result.document.text))
        assertEquals(ContentTimes(), NoteTimes.read(result.document.text))
        compose.onNodeWithTag("organization-list").performScrollToIndex(0)
        compose.onNodeWithTag("organization-result").assertTextContains("完成 1", substring = true)
    }
}
