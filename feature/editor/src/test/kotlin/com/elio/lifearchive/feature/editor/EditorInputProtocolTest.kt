package com.elio.lifearchive.feature.editor

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.core.designsystem.LifeArchiveTheme
import com.elio.lifearchive.core.document.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28], qualifiers = "w412dp-h892dp-mdpi")
class EditorInputProtocolTest {
    @get:Rule val compose = createComposeRule()
    private fun input(session: EditorSession, mode: EditorMode = EditorMode.SOURCE): InputConnection {
        lateinit var view: View
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            LifeArchiveTheme { Box(Modifier.requiredSize(412.dp, 600.dp)) { ComposeEditorEngine.Content(session, mode, MarkdownParser().parse(session.text.text.toString()), 1f, true, true) } }
        }
        compose.onNodeWithTag("editor-body").performClick()
        lateinit var connection: InputConnection
        compose.runOnIdle { connection = checkNotNull(view.onCreateInputConnection(EditorInfo())) { "未建立真实输入连接" } }
        return connection
    }

    @Test fun compositionConfirmationIsNotANewlineAndContinuedEnterIsOneUndoStep() {
        val session = EditorSession("- ")
        session.text.edit { selection = TextRange(2) }
        val connection = input(session)
        compose.runOnIdle { connection.setComposingText("中", 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(session.text.composition)
            val before = session.text.text.toString()
            assertNotNull(session.command(MarkdownCommand.Inline(InlineStyle.BOLD)))
            assertEquals(before, session.text.text.toString())
            connection.setComposingText("中文", 1)
        }
        compose.waitForIdle()
        compose.runOnIdle { connection.commitText("中文", 1) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("- 中文", session.text.text.toString()); assertNull(session.text.composition); connection.commitText("\n", 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("- 中文\n- ", session.text.text.toString())
            session.undo(); assertEquals("- 中文", session.text.text.toString())
            session.redo(); assertEquals("- 中文\n- ", session.text.text.toString())
        }
    }

    @Test fun multipleLinePasteIsUntouchedAndRepeatedEnterExitsInLiveMode() {
        val session = EditorSession("> - [x] 甲")
        session.text.edit { selection = TextRange(session.text.text.length) }
        input(session, EditorMode.LIVE)
        compose.onNodeWithTag("editor-body").performTextInput("\n")
        compose.runOnIdle { assertEquals("> - [x] 甲\n> - [ ] ", session.text.text.toString()) }
        compose.onNodeWithTag("editor-body").performTextInput("\n")
        compose.runOnIdle { assertEquals("> - [x] 甲\n> ", session.text.text.toString()) }
        compose.onNodeWithTag("editor-body").performTextInput("粘贴\n- 原样\n- [x] 完成")
        compose.runOnIdle { assertTrue(session.text.text.toString().endsWith("粘贴\n- 原样\n- [x] 完成")) }
    }
}
