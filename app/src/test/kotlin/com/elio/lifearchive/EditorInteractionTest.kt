package com.elio.lifearchive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.core.designsystem.LifeArchiveTheme
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
