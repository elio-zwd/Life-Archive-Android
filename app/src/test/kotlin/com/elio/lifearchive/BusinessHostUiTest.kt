package com.elio.lifearchive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.domain.notes.NoteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LifeArchiveApplication::class)
class BusinessHostUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun closeRuntime() {
        val context = compose.activity.applicationContext
        compose.activityRule.scenario.close()
        resetJianyuRuntimeForTest(context)
    }

    @Test fun realEntryAndUnsentInputSurviveSwitchingAndActivityRecreation() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("Life 冷启动不能打开见域数据库", compose.activity.getDatabasePath("jianyu_roundtable_database").exists())
        focusWindow()
        compose.onNodeWithTag("business-switch").assertIsDisplayed().assertContentDescriptionEquals("切换到见域").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("jianyu_app_content_root").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("见域暂时无法打开，请重试。").fetchSemanticsNodes().isNotEmpty()
        }
        val logs = ShadowLog.getLogsForTag("JianyuHost").joinToString("\n") { it.msg }
        check(compose.onAllNodesWithTag("jianyu_app_content_root").fetchSemanticsNodes().isNotEmpty()) { "见域未就绪：$logs" }
        compose.onNodeWithTag("jianyu_app_content_root").assertIsDisplayed()
        compose.onNodeWithTag("chat_input").performTextInput("尚未发送的输入，重建后仍应保留")
        focusWindow()
        compose.onNodeWithTag("business-switch").assertContentDescriptionEquals("切换到Life").performClick()
        compose.onNodeWithTag("new-note").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        focusWindow()
        compose.onNodeWithTag("business-switch").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_input").assertTextContains("尚未发送的输入，重建后仍应保留")
    }

    @Test fun lifeKnowledgeOpensSavedNoteThroughTheRealConversationAndReader() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        val result = runBlocking {
            compose.activity.notes.create("学习研究/个人笔记/本地资料验收.md",
                RawDocument("# 合成资料\n\n专用检索短语只出现在保存后的正文。"))
        }
        check(result is NoteResult.Committed)
        focusWindow()
        compose.onNodeWithTag("business-switch").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_input").performTextInput("保留这段未发送的输入")
        compose.onNodeWithContentDescription("添加功能").performClick()
        compose.onNodeWithText("选择资料").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Life 笔记").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Life 笔记").performClick()
        compose.onNodeWithText("搜索标题或正文").performTextInput("专用检索短语")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("合成资料").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("合成资料").performClick()
        compose.onNodeWithText("打开 Life 原文").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地资料验收").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("本地资料验收").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        focusWindow()
        compose.onNodeWithTag("business-switch").performClick()
        compose.onNodeWithTag("chat_input").assertTextContains("保留这段未发送的输入", substring = true)
    }

    private fun focusWindow() {
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("business-switch").fetchSemanticsNodes().isNotEmpty() }
    }
}
