package com.elio.lifearchive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.preferences.VideoPromptType
import com.elio.lifearchive.domain.notes.NoteResult
import com.elio.lifearchive.feature.notes.defaultPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LifeArchiveApplication::class, qualifiers = "w432dp-h960dp-mdpi")
class VideoNotesNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun open() {
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("home-more")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-more").performClick()
        compose.onNodeWithText("视频摘记", substring = false).performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("video-copy") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clipboard(text: String?) {
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (text == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("总结", text))
        }
    }

    @Test fun captureSurvivesRecreationAndConflictThenWritesRawBodyAndSourceToSelectedFolder() {
        val folder = "视频技巧"
        val name = "摘记-${java.util.UUID.randomUUID().toString().take(6)}"
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("home-more")).fetchSemanticsNodes().isNotEmpty() }
        val existing = runBlocking {
            compose.activity.library.createFolder(folder)
            (compose.activity.notes.create("$folder/$name.md", RawDocument("原笔记不能覆盖")) as NoteResult.Committed).value
        }
        val folderChip = hasText(folder, substring = true) and hasAnyAncestor(hasTestTag("folder-filters"))
        compose.waitUntil(15000) { compose.onAllNodes(folderChip).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(folderChip).performClick()
        open()
        compose.onNodeWithTag("video-tab-1").performClick()
        compose.onNodeWithText("保存到：$folder。任何文字都可收录，不要求固定总结格式。").assertIsDisplayed()
        compose.onNodeWithTag("video-name").performTextInput(name)
        val body = "# 正文标题独立\n\n普通总结，不要求固定章节。\n- 条件：中文 😀\n"
        clipboard(body)
        compose.onNodeWithTag("video-paste").performClick()
        compose.onNodeWithTag("video-source").performScrollTo().performTextInput("原分享文字 https://example.test/video")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("video-name").performScrollTo().assertTextContains(name)
        compose.onNodeWithTag("video-body").performScrollTo().assertTextContains(body)
        compose.onNodeWithTag("video-source").performScrollTo().assertTextContains("原分享文字 https://example.test/video")
        compose.onNodeWithTag("video-save-note").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("video-error")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("原笔记不能覆盖", runBlocking { (compose.activity.notes.read(existing.summary.id) as NoteResult.Success).value.document.text })
        compose.onNodeWithTag("video-name").performScrollTo().performTextReplacement("${name}新")
        compose.onNodeWithTag("video-save-note").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("editor-body")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-body").assertTextContains(body + "\n\n## 来源\n原分享文字 https://example.test/video")
        val saved = runBlocking { compose.activity.notes.observeNotes().first().single { it.path.value == "$folder/${name}新.md" } }
        assertEquals(body + "\n\n## 来源\n原分享文字 https://example.test/video", runBlocking { (compose.activity.notes.read(saved.id) as NoteResult.Success).value.document.text })
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("video-name")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("video-name").performScrollTo().assertTextContains("")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("home-more")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun promptsCopyPersistAndRestoreOnlyTheSelectedTemplate() {
        open()
        for (type in VideoPromptType.entries) {
            compose.onNodeWithTag("video-type-${type.name}").performScrollTo().performClick()
            compose.onNodeWithTag("video-copy").performScrollTo().performClick()
            compose.onNodeWithTag("video-message").assertTextContains("提示词已复制", substring = true)
            val copied = compose.runOnIdle { (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString() }
            assertEquals(type.defaultPrompt, copied)
        }
        compose.onNodeWithTag("video-prompt").performScrollTo().performTextReplacement("避坑自定义提示词\n不要漏掉条件")
        compose.onNodeWithTag("video-save-prompt").performScrollTo().performClick()
        compose.waitUntil(15000) { runBlocking { compose.activity.preferences.preferences.first().videoPrompts[VideoPromptType.CHOOSING] == "避坑自定义提示词\n不要漏掉条件" } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("video-prompt").performScrollTo().assertTextContains("避坑自定义提示词\n不要漏掉条件")
        compose.onNodeWithContentDescription("返回").performClick()
        open()
        compose.onNodeWithTag("video-type-CHOOSING").performScrollTo().performClick()
        compose.onNodeWithTag("video-prompt").performScrollTo().assertTextContains("避坑自定义提示词\n不要漏掉条件")
        compose.onNodeWithTag("video-restore-prompt").performScrollTo().performClick()
        compose.onNodeWithText("确认恢复", substring = false).performClick()
        compose.waitUntil(15000) { runBlocking { compose.activity.preferences.preferences.first().videoPrompts.isEmpty() } }
        compose.onNodeWithTag("video-prompt").performScrollTo().assertTextContains(VideoPromptType.CHOOSING.defaultPrompt)
    }

    @Test fun clipboardReplacementAndUnsavedBackRemainExplicitAcrossRecreation() {
        open(); compose.onNodeWithTag("video-tab-1").performClick()
        compose.onNodeWithTag("video-body").performTextInput("原正文")
        clipboard(null); compose.onNodeWithTag("video-paste").performScrollTo().performClick()
        compose.onNodeWithTag("video-error").assertTextContains("剪贴板为空", substring = true)
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newRawUri("图片", android.net.Uri.parse("content://example.test/image")))
        }
        compose.onNodeWithTag("video-paste").performScrollTo().performClick()
        compose.onNodeWithTag("video-error").assertTextContains("不含可收录的文字", substring = true)
        compose.onNodeWithTag("video-body").performScrollTo().assertTextContains("原正文")
        clipboard("替换正文"); compose.onNodeWithTag("video-paste").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("替换已有正文？").assertIsDisplayed()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithTag("video-body").performScrollTo().assertTextContains("原正文")
        compose.onNodeWithTag("video-paste").performScrollTo().performClick()
        compose.onNodeWithText("替换正文", substring = false).performClick()
        compose.onNodeWithTag("video-body").performScrollTo().assertTextContains("替换正文")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("放弃未保存的输入？").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("继续编辑", substring = false).performClick()
        compose.onNodeWithTag("video-body").performScrollTo().assertTextContains("替换正文")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃并返回", substring = false).performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("home-more")).fetchSemanticsNodes().isNotEmpty() }
    }
}
