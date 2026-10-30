package com.elio.lifearchive

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import com.elio.lifearchive.core.designsystem.LifeArchiveTheme
import com.elio.lifearchive.core.designsystem.DocumentPageTheme
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.preferences.DocumentTheme
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.core.document.MarkdownParser
import com.elio.lifearchive.core.document.BlockKind
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.feature.editor.*
import com.elio.lifearchive.feature.notes.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrototypeVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "w432dp-h960dp-mdpi")
    fun renderSourceThemesAndThemePickerOnDesktop() {
        val output = File(System.getenv("TEMP"), "life-archive-ui-polish-preview-20261030")
        output.mkdirs()
        val raw = "# 周末计划\n\n去西湖徒步，拍一些照片，整理上周的笔记，晚上看一部电影。\n\n## 需要准备\n- [ ] 相机和移动电源\n- [ ] 简单的水和零食\n- [ ] 提前查看天气\n\n> 记录当下，构建更好的自己。"
        val session = EditorSession(raw)
        val parsed = MarkdownParser().parse(raw)
        var palette by mutableStateOf(DocumentPalette.BLUE)
        var selected by mutableStateOf(DocumentTheme.BLUE)
        compose.setContent {
            DocumentPageTheme(palette) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        EditorScreen(EditorUiState(loading = false, name = "周末计划", mode = EditorMode.SOURCE, parsed = parsed), session, SaveStatus(), documentTheme = selected, onDocumentTheme = { selected = it })
                    }
                    // 仅占位，以便比较可用写作区域；不模拟系统输入法。
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Box(Modifier.fillMaxWidth().height(260.dp).padding(22.dp)) { Text("键盘占位 · 真机输入待验收", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("格式").performClick()
        fun save(view: android.view.View, name: String) {
            require(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        for (value in DocumentPalette.entries) {
            compose.runOnIdle { palette = value; selected = DocumentTheme.valueOf(value.name) }
            compose.waitForIdle()
            compose.runOnIdle { save(compose.activity.findViewById(android.R.id.content), "source-${value.name.lowercase()}.png") }
        }
        compose.runOnIdle { palette = DocumentPalette.BLUE; selected = DocumentTheme.BLUE }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("主题", substring = false).performClick()
        compose.onNodeWithText("A · 浅灰蓝").assertIsDisplayed()
        compose.runOnIdle {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() ?: error("未找到主题对话框")
            save(dialog.window?.decorView ?: error("主题对话框没有窗口"), "theme-picker.png")
        }
    }

    @Test fun renderLightAndDarkPagesOnDesktop() {
        val output = File(System.getenv("TEMP"), "life-archive-editor-preview")
        output.mkdirs()
        var page by mutableIntStateOf(0)
        var dark by mutableStateOf(false)
        val raw = PrototypeContent.document()
        val parsed = MarkdownParser().parse(raw)
        val session = EditorSession(raw)
        val sampleImage = sampleLandscape()
        val imageOffset = parsed.blocks.first { it.kind == BlockKind.IMAGE }.start
        val summary = NoteSummary(NoteId("visual-only"), LibraryPath("日常片段.md"), DocumentVersion("preview"), false)
        compose.setContent {
            LifeArchiveTheme(darkTheme = dark) {
                Box(Modifier.requiredSize(412.dp, 892.dp)) {
                    when (page) {
                        0 -> NotesScreen(NotesUiState(notes = listOf(summary), loading = false), {}, { _, _ -> }, {}, {}, if (dark) "深色" else "浅色", {})
                        1, 3 -> EditorScreen(EditorUiState(loading = false, name = "日常片段", mode = EditorMode.READ, parsed = parsed), session, SaveStatus(), initialReadingOffset = if (page == 3) imageOffset else 0, loadImage = { sampleImage })
                        else -> Column {
                            Box(Modifier.weight(1f)) { EditorScreen(EditorUiState(loading = false, name = "日常片段", mode = EditorMode.LIVE, parsed = parsed), session, SaveStatus()) }
                            // 明确的占位区域仅用于检查键盘占用空间，不模拟真实输入法验收。
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) { Box(Modifier.fillMaxWidth().height(260.dp).padding(22.dp)) { Text("键盘占位 · 真机输入待验收", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        }
                    }
                }
            }
        }
        for (theme in listOf(false, true)) for (screen in 0..3) {
            compose.runOnIdle { dark = theme; page = screen; if (screen == 2) session.text.edit { selection = TextRange(raw.indexOf("# 把日常")) } }
            compose.waitForIdle()
            if (screen == 2) { compose.onNodeWithContentDescription("格式").performClick(); compose.waitForIdle() }
            if (screen == 3) compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("傍晚的山野 · 本地合成示例图片").fetchSemanticsNodes().isNotEmpty() }
            val name = (if (theme) "dark" else "light") + "-" + listOf("notes", "reader", "editor", "reader-detail")[screen] + ".png"
            compose.runOnIdle {
                val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
