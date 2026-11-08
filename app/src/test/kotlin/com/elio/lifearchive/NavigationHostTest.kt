package com.elio.lifearchive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LifeArchiveApplication::class)
class NavigationHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cancellingNamingDoesNotCreateAFile() {
        compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("new-note")).fetchSemanticsNodes().isNotEmpty() }
        val before = kotlinx.coroutines.runBlocking { compose.activity.notes.observeNotes().first().size }
        compose.onNodeWithTag("new-note").performClick()
        compose.onNodeWithTag("note-name").performTextInput("取消后不创建")
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithTag("new-note").assertIsDisplayed()
        val after = kotlinx.coroutines.runBlocking { compose.activity.notes.observeNotes().first().size }
        org.junit.Assert.assertEquals(before, after)
    }

    @Test fun backStackSurvivesActivityRecreationAndReturnsHome() {
        compose.waitUntil(15000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("new-note")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("new-note").performClick()
        compose.onNodeWithTag("note-name").performTextInput("导航验收-${java.util.UUID.randomUUID().toString().take(6)}")
        compose.onNodeWithText("创建", substring = false).performClick()
        compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("editor-body")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-body").performTextInput("中文输入与 Activity 重建")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("editor-body").assertIsDisplayed()
        compose.onNodeWithTag("editor-body").assertTextContains("中文输入与 Activity 重建", substring = true)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("new-note")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("new-note").assertIsDisplayed()
    }

    @Test fun insertionFormSurvivesActivityRecreationAndCancellationKeepsTheBody() {
        compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("new-note")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("new-note").performClick()
        compose.onNodeWithTag("note-name").performTextInput("工具栏重建-${java.util.UUID.randomUUID().toString().take(6)}")
        compose.onNodeWithText("创建", substring = false).performClick()
        compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("editor-body")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-body").performTextInput("保留正文")
        compose.onNodeWithTag("tool-insert").performClick()
        compose.onNodeWithTag("editor-tool-panel").performScrollToNode(hasContentDescription("链接"))
        compose.onNodeWithContentDescription("链接").performClick()
        compose.onNodeWithTag("insert-target").performTextInput("https://example.com/保留")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("insert-target").assertIsDisplayed()
        compose.onNodeWithText("https://example.com/保留", substring = false).assertIsDisplayed()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithTag("editor-body").assertTextContains("保留正文", substring = true)
    }
}
