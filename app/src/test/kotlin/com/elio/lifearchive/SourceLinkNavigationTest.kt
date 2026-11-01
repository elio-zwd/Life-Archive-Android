package com.elio.lifearchive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.document.NoteSource
import com.elio.lifearchive.domain.notes.NoteResult
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
class SourceLinkNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun clipboard(value: String?) = compose.runOnIdle {
        val clip = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (value == null) clip.clearPrimaryClip() else clip.setPrimaryClip(ClipData.newPlainText("分享", value))
    }
    private fun moreUpdate() {
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("更新链接", substring = false).performClick()
    }

    @Test fun previewAndReaderShareLinkDialogSurvivesRecreationThenSavesOnlySource() {
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("home-paste")).fetchSemanticsNodes().isNotEmpty() }
        val title = "视频来源-${java.util.UUID.randomUUID().toString().take(6)}"
        val raw = "---\ntitle: $title\ntags: [生活, 摄影]\nsource: \"https://wrong.test/video\"\nunknown: keep\n---\n# 正文不变\n\n**关键词**与==原理==\n"
        val before = runBlocking { compose.activity.notes.observeNotes().first().size }
        clipboard(raw); compose.onNodeWithTag("home-paste").performClick()
        clipboard("学会这三个机位，你的人像摄影就会很牛！ https://xhslink.cn/o/6va5s9Fs9CN 复制文本后进入【小红书】，直接查看笔记。")
        compose.onNodeWithTag("update-source").performScrollTo().performClick()
        compose.onNodeWithTag("source-link-current").assertTextContains("https://wrong.test/video", substring = true)
        clipboard("https://v.douyin.com/ySydEn8zeT8/")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("source-link-text").assertTextContains("https://xhslink.cn/o/6va5s9Fs9CN", substring = true)
        compose.onNodeWithTag("source-link-confirm").performClick()
        compose.onNodeWithTag("video-message").assertTextContains("链接已应用，保存笔记后生效", substring = true)
        assertEquals(before, runBlocking { compose.activity.notes.observeNotes().first().size })
        compose.onNodeWithTag("video-save-note").performScrollTo().performClick()
        compose.waitUntil(15000) { runBlocking { compose.activity.notes.observeNotes().first().any { it.path.value == "$title.md" } } }
        val note = runBlocking { compose.activity.notes.observeNotes().first().single { it.path.value == "$title.md" } }
        val first = raw.replace("https://wrong.test/video", "https://xhslink.cn/o/6va5s9Fs9CN")
        assertEquals(first, runBlocking { (compose.activity.notes.read(note.id) as NoteResult.Success).value.document.text })
        compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription("更多")).fetchSemanticsNodes().isNotEmpty() }
        moreUpdate()
        compose.onNodeWithTag("source-link-confirm").performClick()
        compose.waitUntil(15000) { runBlocking { NoteSource.read((compose.activity.notes.read(note.id) as NoteResult.Success).value.document.text) == "https://v.douyin.com/ySydEn8zeT8/" } }
        compose.onNodeWithText("链接已更新", substring = false).assertIsDisplayed()
        compose.onAllNodes(hasTestTag("editor-body")).assertCountEquals(0)
        val second = first.replace("https://xhslink.cn/o/6va5s9Fs9CN", "https://v.douyin.com/ySydEn8zeT8/")
        assertEquals(second, runBlocking { (compose.activity.notes.read(note.id) as NoteResult.Success).value.document.text })
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("编辑笔记", substring = false).performClick()
        compose.onNodeWithTag("editor-body").assertTextContains(second)
        clipboard("https://example.test/v?a=1&b=2#part"); moreUpdate()
        compose.onNodeWithTag("source-link-confirm").performClick()
        compose.waitUntil(15000) { runBlocking { NoteSource.read((compose.activity.notes.read(note.id) as NoteResult.Success).value.document.text) == "https://example.test/v?a=1&b=2#part" } }
        compose.onNodeWithTag("editor-body").assertTextContains("https://example.test/v?a=1&b=2#part", substring = true)
    }

    @Test fun invalidClipboardMultipleChoicesAndCancelKeepPreviewUnchanged() {
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("home-paste")).fetchSemanticsNodes().isNotEmpty() }
        clipboard("# 预览不保存\n正文"); compose.onNodeWithTag("home-paste").performClick()
        val before = runBlocking { compose.activity.notes.observeNotes().first().size }
        clipboard(null); compose.onNodeWithTag("update-source").performScrollTo().performClick()
        compose.onNodeWithTag("source-link-error").assertIsDisplayed()
        compose.onNodeWithTag("source-link-confirm").assertIsNotEnabled()
        compose.runOnIdle { (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newRawUri("图片", android.net.Uri.parse("content://example.test/image"))) }
        compose.onNodeWithTag("source-link-paste").performClick()
        compose.onNodeWithTag("source-link-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("source-link-text").performTextReplacement("https://example.test/a https://example.test/b")
        compose.onNodeWithTag("source-link-confirm").assertIsNotEnabled()
        compose.onNodeWithText("https://example.test/b", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("source-link-confirm").assertIsEnabled()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithTag("video-body").performScrollTo().assertTextContains("tags: []", substring = true)
        assertEquals(before, runBlocking { compose.activity.notes.observeNotes().first().size })
    }
}
