package com.elio.lifearchive.feature.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.designsystem.DocumentPageTheme
import com.elio.lifearchive.core.designsystem.DocumentPalette
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoNotesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bothTabsRenderInLightAndDarkAndActionsStayReachableWhenBusyOrShowingErrors() {
        var state by mutableStateOf(VideoNotesUiState(loadingPrompts = false, promptsLoaded = true))
        var palette by mutableStateOf(DocumentPalette.BLUE)
        var captureOnly by mutableStateOf(false)
        var home by mutableStateOf(false)
        val output = File(System.getenv("LIFE_ARCHIVE_VIDEO_PREVIEW") ?: "D:/Temp/life-archive-video-notes-20261101").also { it.mkdirs() }
        compose.setContent {
            DocumentPageTheme(palette) {
                if (home) NotesScreen(NotesUiState(loading = false), {}, { _, _ -> }, {}, {}, "浅色", {})
                else VideoNotesScreen(state, "生活技巧", {}, { state = state.copy(tab = it) }, { state = state.copy(selectedType = it) }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, captureOnly = captureOnly)
            }
        }
        fun save(name: String) {
            compose.waitForIdle()
            compose.runOnIdle {
                val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        for (nextPalette in DocumentPalette.entries) {
            val style = when (nextPalette) { DocumentPalette.BLUE -> "light"; DocumentPalette.WARM -> "warm"; DocumentPalette.GRAPHITE -> "dark" }
            compose.runOnIdle { palette = nextPalette; state = state.copy(tab = 0) }
            compose.onNodeWithTag("video-copy").performScrollTo().assertIsDisplayed().assertIsEnabled()
            save("prompts-$style.png")
            compose.runOnIdle { state = state.copy(tab = 1, name = "手机照片整理技巧", body = "# 整理照片\n\n1. 先筛选重复照片。\n2. 按月份建立相册。\n\n注意保留原始文件。", source = "小红书分享文字与原视频链接") }
            compose.onNodeWithTag("video-save-note").performScrollTo().assertIsDisplayed().assertIsEnabled()
            save("capture-$style.png")
            compose.runOnIdle {
                captureOnly = true
                state = state.copy(body = "---\ntitle: 手机照片整理技巧\ntags: [生活, 摄影]\n---\n# 整理照片\n\n**原始文件**应妥善保留。\n\n==整理前先备份。==")
            }
            compose.onNodeWithTag("video-name").performScrollTo()
            compose.onNodeWithText("粘贴预览", substring = false).assertIsDisplayed()
            compose.onNodeWithTag("paste-tags").assertTextContains("生活、摄影", substring = true)
            save("paste-$style.png")
            compose.runOnIdle { home = true }
            compose.onNodeWithTag("home-paste").assertIsDisplayed().assertIsEnabled()
            save("home-paste-$style.png")
            compose.runOnIdle { home = false; captureOnly = false }
        }
        compose.runOnIdle { state = state.copy(savingNote = true) }
        compose.onNodeWithTag("video-save-note").assertIsNotEnabled()
        compose.onNodeWithTag("video-tab-0").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(savingNote = false, error = "保存失败，输入已保留，可以修改后重试") }
        compose.onNodeWithTag("video-error").assertIsDisplayed()
        compose.onNodeWithTag("video-save-note").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("video-body").performScrollTo().assertTextContains("==整理前先备份。==", substring = true)
    }
}
