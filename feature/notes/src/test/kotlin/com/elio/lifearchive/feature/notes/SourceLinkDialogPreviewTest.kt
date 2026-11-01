package com.elio.lifearchive.feature.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.designsystem.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceLinkDialogPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun threeThemesKeepLongLinksChoicesErrorsAndBusyActionsReachable() {
        var palette by mutableStateOf(DocumentPalette.BLUE)
        var showing by mutableStateOf(false)
        val long = "https://example.test/video?from=share&description=" + "long-value-".repeat(12) + "#part"
        var state by mutableStateOf(SourceLinkDialogState(current = "https://wrong.test/video", text = "分享文字 $long https://v.douyin.com/ySydEn8zeT8/", links = listOf(long, "https://v.douyin.com/ySydEn8zeT8/")))
        val dir = File(System.getenv("LIFE_ARCHIVE_SOURCE_PREVIEW") ?: "D:/Temp/life-archive-update-link-20261101").also { it.mkdirs() }
        compose.setContent { DocumentPageTheme(palette) {
            Surface(Modifier.fillMaxSize()) { Text("笔记") }
            if (showing) SourceLinkDialog(state, preview = true, onText = {}, onSelect = { state = state.copy(selected = it) }, onPaste = {}, onConfirm = {}, onDismiss = {})
        } }
        compose.runOnIdle { showing = true }
        for (next in DocumentPalette.entries) {
            compose.runOnIdle { palette = next; state = state.copy(selected = null) }
            compose.onNodeWithTag("source-link-confirm").assertIsNotEnabled()
            compose.onNodeWithText("https://v.douyin.com/ySydEn8zeT8/", substring = false).performScrollTo().performClick()
            compose.onNodeWithTag("source-link-confirm").assertIsEnabled()
            compose.runOnIdle {
                val view = ShadowDialog.getLatestDialog().window!!.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(dir, "source-${next.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithTag("source-link-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("source-link-paste").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(saving = false, error = "保存失败，链接和正文均保留，可重试") }
        compose.onNodeWithTag("source-link-error").assertIsDisplayed()
        compose.onNodeWithTag("source-link-confirm").assertIsEnabled()
    }
}
