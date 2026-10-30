package com.elio.lifearchive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elio.lifearchive.core.designsystem.LifeArchiveTheme
import com.elio.lifearchive.core.designsystem.DocumentPageTheme
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.designsystem.colors
import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.preferences.DocumentTheme
import com.elio.lifearchive.domain.notes.Draft
import com.elio.lifearchive.domain.notes.NoteId
import com.elio.lifearchive.feature.editor.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h892dp-mdpi")
class EditorInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceHeadingUsesDocumentColorAndBodySizeAcrossThemes() {
        val raw = "# 标题\n\n普通正文"
        val session = EditorSession(raw)
        val parsed = MarkdownParser().parse(raw)
        var palette by mutableStateOf(DocumentPalette.BLUE)
        compose.setContent {
            DocumentPageTheme(palette) { ComposeEditorEngine.Content(session, EditorMode.SOURCE, parsed, 1f, false, true) }
        }
        for (theme in DocumentPalette.entries) {
            compose.runOnIdle { palette = theme }
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("editor-body").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            val input = results.single().layoutInput
            val heading = input.text.spanStyles.first { it.start <= raw.indexOf("标题") && it.end >= raw.indexOf("标题") + 2 }
            assertEquals(theme.colors.accent, heading.item.color)
            assertEquals(16.sp, input.style.fontSize)
            assertEquals("源码标题不应被放大", androidx.compose.ui.unit.TextUnit.Unspecified, heading.item.fontSize)
            assertEquals(raw, session.content.source)
        }
    }

    @Test fun pendingDraftDisablesHeadingSelection() {
        val session = EditorSession("原正文")
        val draft = Draft(NoteId("draft"), DocumentVersion("version"), 1, RawDocument("草稿"))
        compose.setContent { DocumentPageTheme(DocumentPalette.BLUE) {
            EditorScreen(EditorUiState(loading = false, mode = EditorMode.SOURCE, pendingDraft = draft), session, SaveStatus())
        } }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("选择标题级别").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("原正文", session.content.source) }
    }

    @Test fun changingDocumentThemeKeepsTheSessionAndSelection() {
        val session = EditorSession("甲乙丙")
        session.text.edit { selection = TextRange(1, 2) }
        var theme by mutableStateOf(DocumentTheme.BLUE)
        compose.setContent {
            val palette = if (theme == DocumentTheme.WARM) DocumentPalette.WARM else DocumentPalette.BLUE
            DocumentPageTheme(palette) {
                EditorScreen(EditorUiState(loading = false, mode = EditorMode.SOURCE), session, SaveStatus(), documentTheme = theme, onDocumentTheme = { theme = it })
            }
        }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("主题", substring = false).performClick()
        compose.onAllNodesWithText("Aa").assertCountEquals(3)
        compose.onNodeWithText("温润手记 · 柔和灰绿强调").assertIsDisplayed()
        compose.onNodeWithText("B · 暖纸色").performClick()
        compose.runOnIdle {
            assertEquals(DocumentTheme.WARM, theme)
            assertEquals("甲乙丙", session.content.source)
            assertEquals(TextRange(1, 2), session.text.selection)
        }
    }

    @Test fun inputDoesNotWaitForInitialHighlightOrLoseFocusWhenAnEmptyNoteGetsText() {
        val session = EditorSession("")
        var state by mutableStateOf(EditorUiState(loading = false, mode = EditorMode.SOURCE))
        compose.setContent { LifeArchiveTheme { Box(Modifier.requiredSize(412.dp, 750.dp)) { EditorScreen(state, session, SaveStatus()) } } }
        compose.onNodeWithTag("editor-body").performTextInput("中文😀")
        compose.onNodeWithTag("editor-body").assertIsDisplayed().assertIsFocused()
        compose.runOnIdle { assertEquals("中文😀", session.content.source); state = state.copy(mode = EditorMode.LIVE) }
        compose.onNodeWithTag("editor-body").performTextInput("继续")
        compose.onNodeWithTag("editor-body").assertIsFocused()
        compose.runOnIdle { state = state.copy(parsed = MarkdownParser().parse(session.content.source)) }
        compose.onNodeWithTag("editor-body").assertIsFocused()
        compose.runOnIdle { assertEquals("中文😀继续", session.content.source) }
    }

    @Test fun emptyLinesPreserveRenderedHighlightsWhileTheParserIsDelayedInBothModes() {
        val raw = "# 标题\n\n**中文高亮**\n末尾"
        var session by mutableStateOf(EditorSession(raw))
        var mode by mutableStateOf(EditorMode.SOURCE)
        val parsed = MarkdownParser().parse(raw)
        compose.setContent { LifeArchiveTheme { Box(Modifier.requiredSize(412.dp, 650.dp)) { ComposeEditorEngine.Content(session, mode, parsed, 1f, false, true) } } }
        for (nextMode in listOf(EditorMode.SOURCE, EditorMode.LIVE)) {
            val current = EditorSession(raw)
            compose.runOnIdle { session = current; mode = nextMode; current.text.edit { selection = TextRange(raw.length) } }
            repeat(12) {
                compose.onNodeWithTag("editor-body").performTextInput("\n")
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag("editor-body").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
                assertTrue("解析落后时不能清空高亮", results.single().layoutInput.text.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
                assertEquals(raw + "\n".repeat(it + 1), current.text.text.toString())
            }
        }
    }

    @Test fun longDocumentsAcceptInputAndSwitchModesWithoutChangingOtherBytes() {
        var session by mutableStateOf(EditorSession(""))
        var parsed by mutableStateOf<ParsedDocument?>(null)
        var mode by mutableStateOf(EditorMode.SOURCE)
        compose.setContent {
            LifeArchiveTheme {
                Box(Modifier.requiredSize(412.dp, 650.dp)) { ComposeEditorEngine.Content(session, mode, parsed, 1f, false, true) }
            }
        }
        for (size in listOf(50 * 1024, 1024 * 1024)) {
            val original = "段落 **中文** and text\r\n\r\n".repeat(size / 24 + 1).take(size)
            val next = EditorSession(original)
            val projection = MarkdownParser().parse(original)
            compose.runOnIdle { session = next; parsed = projection; mode = EditorMode.SOURCE; next.text.edit { selection = TextRange(original.length / 2) } }
            compose.onNodeWithTag("editor-body").performTextInput("新增")
            val expected = original.substring(0, original.length / 2) + "新增" + original.substring(original.length / 2)
            compose.runOnIdle { assertEquals(expected, next.text.text.toString()); parsed = MarkdownParser().parse(expected); mode = EditorMode.LIVE }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(expected, next.text.text.toString()); mode = EditorMode.SOURCE }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(expected, next.text.text.toString()) }
        }
    }
}
