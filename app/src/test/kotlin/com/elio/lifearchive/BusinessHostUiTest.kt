package com.elio.lifearchive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.designsystem.colors
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
        compose.onNodeWithTag("pet-memory-add").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("希望伙伴记住的事").performTextInput("合成资料：周末喜欢散步")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pet-memory-text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-memory-text").assertTextContains("合成资料：周末喜欢散步")
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
        compose.onNodeWithText("合成资料：周末喜欢散步").performScrollTo().assertIsDisplayed()
        val repository = compose.activity.companionProvider.get()
        val memoryId = repository.state.value.memories.single().id
        captureCompanion("memories")
        compose.onNodeWithTag("pet-memory-edit-$memoryId").performScrollTo().performClick()
        compose.onNodeWithText("希望伙伴记住的事").performTextClearance()
        compose.onNodeWithText("希望伙伴记住的事").performTextInput("更正后：周末喜欢在公园散步")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("更正后：周末喜欢在公园散步").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-memory-delete-$memoryId").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("更正后：周末喜欢在公园散步").assertIsDisplayed()
        compose.onNodeWithTag("pet-memory-delete-$memoryId").performClick()
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("更正后：周末喜欢在公园散步").fetchSemanticsNodes().isEmpty() }
        assertTrue(repository.state.value.memories.none { it.id == memoryId })
    }

    @Test
    @Config(sdk = [34], application = LifeArchiveApplication::class, qualifiers = "w432dp-h960dp-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun memoryNotebookUsesTheSharedThemes() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        focusWindow(); switchTo("PET")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pet-input").fetchSemanticsNodes().isNotEmpty() }
        val repository = compose.activity.companionProvider.get()
        runBlocking {
            repository.remember("周末喜欢散步")
            repository.remember("喜欢安静的交流方式")
            repository.remember("忙的时候，希望先听我说完。")
        }
        compose.onNodeWithTag("pet-tab-1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("忙的时候，希望先听我说完。").fetchSemanticsNodes().isNotEmpty() }
        for (theme in com.elio.lifearchive.core.preferences.DocumentTheme.entries) {
            compose.runOnIdle { runBlocking { compose.activity.preferences.setDocumentTheme(theme) } }
            compose.waitForIdle()
            compose.onNodeWithText("共同记忆").assertIsDisplayed()
            compose.onNodeWithTag("pet-memory-portrait").assertIsDisplayed()
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("共同记忆").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            org.junit.Assert.assertEquals(com.elio.lifearchive.core.designsystem.DocumentPalette.valueOf(theme.name).colors.ink,
                results.single().layoutInput.style.color)
            captureCompanion("memories-${theme.name.lowercase()}")
            val memoryId = repository.state.value.memories.first().id
            compose.onNodeWithTag("pet-memory-edit-$memoryId").performScrollTo().performClick()
            captureCompanion("memory-sheet-${theme.name.lowercase()}", dialog = true)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("pet-tab-0").performClick()
            compose.onNodeWithTag("pet-large-avatar").assertIsDisplayed()
            captureCompanion("conversation-${theme.name.lowercase()}")
            compose.onNodeWithTag("pet-tab-2").performClick()
            captureCompanion("settings-${theme.name.lowercase()}")
            compose.onNodeWithTag("pet-profile-edit").performScrollTo().performClick()
            captureCompanion("profile-sheet-${theme.name.lowercase()}", dialog = true)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("pet-tab-1").performClick()
        }
    }

    private fun captureCompanion(name: String, dialog: Boolean = false) {
        val directory = java.io.File(System.getProperty("java.io.tmpdir"), "life-archive-companion-previews").apply { mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            view.draw(canvas)
            if (dialog) org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.draw(canvas)
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

    @Test @Config(sdk = [34], application = LifeArchiveApplication::class, qualifiers = "w432dp-h960dp-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun inAppDialogueAndPageContinueTheSameDraft() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        focusWindow(); switchTo("PET")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pet-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-input").performTextInput("同一份草稿")
        compose.onNodeWithTag("pet-tab-2").performClick()
        compose.onNodeWithTag("pet-in-app-visible").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes().isNotEmpty() }
        focusWindow(); switchTo("LIFE")
        captureCompanion("in-app-idle")
        compose.onNodeWithTag("in-app-companion-chat").performClick()
        compose.onNodeWithTag("in-app-companion-input").performScrollTo().assertTextContains("同一份草稿")
        compose.onNodeWithTag("in-app-companion-input").performTextInput("，在小窗继续")
        captureCompanion("in-app-dialogue")
        compose.onNodeWithText("结束对话").performScrollTo().performClick()
        compose.onNodeWithTag("in-app-companion-dialogue").assertDoesNotExist()
        compose.onNodeWithTag("in-app-companion-chat").performClick()
        compose.onNodeWithText("回到伙伴").performScrollTo().performClick()
        compose.onNodeWithTag("pet-input").assertTextContains("同一份草稿，在小窗继续")
    }

    @Test fun profileSheetRetainsUnsavedTextAcrossActivityRecreation() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        focusWindow(); switchTo("PET")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pet-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-tab-2").performClick()
        compose.onNodeWithTag("pet-profile-edit").performScrollTo().performClick()
        compose.onNodeWithTag("pet-profile-name").performTextClearance()
        compose.onNodeWithTag("pet-profile-name").performTextInput("小日和")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pet-profile-name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-profile-name").assertTextContains("小日和")
        compose.onNodeWithTag("pet-editor-save").performClick()
        compose.waitUntil(10_000) { compose.activity.companionProvider.get().state.value.profile.name == "小日和" }
        compose.onNodeWithTag("pet-editor-sheet").assertDoesNotExist()
    }
}
