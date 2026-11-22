package com.elio.lifearchive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.domain.notes.NoteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        switchTo("JIANYU")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("jianyu_app_content_root").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("见域暂时无法打开，请重试。").fetchSemanticsNodes().isNotEmpty()
        }
        val logs = ShadowLog.getLogsForTag("JianyuHost").joinToString("\n") { it.msg }
        check(compose.onAllNodesWithTag("jianyu_app_content_root").fetchSemanticsNodes().isNotEmpty()) { "见域未就绪：$logs" }
        compose.onNodeWithTag("jianyu_app_content_root").assertIsDisplayed()
        compose.onNodeWithTag("chat_input").performTextInput("尚未发送的输入，重建后仍应保留")
        focusWindow()
        switchTo("LIFE")
        compose.onNodeWithTag("new-note").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        focusWindow()
        switchTo("JIANYU")
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
        switchTo("JIANYU")
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
        switchTo("JIANYU")
        compose.onNodeWithTag("chat_input").assertTextContains("保留这段未发送的输入", substring = true)
    }

    private fun focusWindow() {
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("business-switch").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun switchTo(name: String) {
        compose.onNodeWithTag("business-switch").assertContentDescriptionEquals("切换业务").performClick()
        compose.onNodeWithTag("business-select-$name").performClick()
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun petDraftAndMemorySurviveThreeDomainSwitchAndRecreation() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("Life 冷启动不能打开宠物库", compose.activity.getDatabasePath("companion.db").exists())
        focusWindow(); switchTo("PET")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pet-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-input").performTextInput("给伙伴的未发送输入")
        compose.onNodeWithTag("pet-input").assertIsDisplayed()
        compose.onNodeWithTag("pet-send").assertIsDisplayed()
        captureCompanion("conversation")
        compose.onNodeWithText("记忆", useUnmergedTree = true).performClick()
        compose.onNodeWithText("添加记忆").performClick()
        compose.onNodeWithText("希望伙伴记住的事").performTextInput("合成资料：周末喜欢散步")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成资料：周末喜欢散步").fetchSemanticsNodes().isNotEmpty() }
        focusWindow(); switchTo("LIFE")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        focusWindow(); switchTo("PET")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pet-root").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("陪伴", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pet-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-input").assertTextContains("给伙伴的未发送输入")
        compose.onNodeWithText("记忆", useUnmergedTree = true).performClick()
        compose.onNodeWithText("合成资料：周末喜欢散步").assertIsDisplayed()
        captureCompanion("memories")
    }

    private fun captureCompanion(name: String) {
        val directory = java.io.File(System.getProperty("java.io.tmpdir"), "life-archive-companion-previews").apply { mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(directory, "$name.png").outputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
            bitmap.recycle()
        }
    }

    @Test fun inAppCompanionCanBeClosedWhilePetPageIsInactive() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        focusWindow(); switchTo("PET")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pet-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-tab-2").performClick()
        compose.onNodeWithTag("pet-in-app-visible").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes().isNotEmpty() }
        focusWindow(); switchTo("LIFE")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("in-app-companion").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("in-app-companion-drag").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(-1000f, -1000f)); up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("in-app-companion-drag").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("in-app-companion").fetchSemanticsNode().boundsInRoot
        assertTrue("小窗拖动后必须保留可见手柄", bounds.left >= 0f && bounds.top >= 0f)
        compose.onNode(hasText("收起") and hasAnyAncestor(hasTestTag("in-app-companion"))).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("in-app-companion").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("in-app-companion").assertDoesNotExist()
    }
}
