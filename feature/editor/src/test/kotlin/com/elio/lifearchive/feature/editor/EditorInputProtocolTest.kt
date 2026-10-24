package com.elio.lifearchive.feature.editor

import android.view.View
import android.view.KeyEvent
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

    @Test fun mixedToolbarCommandsAndChineseEnterWorkInSourceMode() = mixedInput(EditorMode.SOURCE)
    @Test fun mixedToolbarCommandsAndChineseEnterWorkInLiveMode() = mixedInput(EditorMode.LIVE)

    private fun mixedInput(mode: EditorMode) {
        val session = EditorSession("")
        val connection = input(session, mode)
        for (style in ListStyle.entries) for (quoteFirst in listOf(false, true)) {
            var expected = ""
            compose.runOnIdle {
                session.edit { replace(0, length, ""); selection = TextRange(0) }
                if (quoteFirst) session.command(MarkdownCommand.Quote)
                session.command(MarkdownCommand.List(style))
                if (!quoteFirst) session.command(MarkdownCommand.Quote)
                expected = session.content.source + "中文😀"
                connection.setComposingText("zhongwen", 1)
            }
            compose.waitForIdle()
            compose.runOnIdle { connection.commitText("中文😀", 1) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(expected, session.content.source)
                connection.commitText("\n", 1)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val continuation = requireNotNull(MarkdownContinuation.calculate(expected, expected.length))
                val next = expected + continuation.replacement
                assertEquals(next, session.content.source)
                session.undo(); assertEquals(expected, session.content.source)
                session.redo(); assertEquals(next, session.content.source)
                connection.deleteSurroundingText(1, 0)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("editor-body").performClick()
            compose.onNodeWithTag("editor-body").assertIsFocused()
        }
    }

    @Test fun selectionReplacementCompositionAndBatchPasteKeepTheirOriginalInput() {
        val session = EditorSession("1. > 中文😀")
        val connection = input(session)
        compose.runOnIdle { session.edit { selection = TextRange(length - 4, length) }; connection.commitText("\n", 1) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("1. > \n", session.content.source) }
        compose.runOnIdle {
            session.edit { replace(0, length, "1. > 内容"); selection = TextRange(length) }
            connection.beginBatchEdit()
            connection.commitText("粘贴\n- 原样\n> 保留", 1)
            connection.endBatchEdit()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("1. > 内容粘贴\n- 原样\n> 保留", session.content.source) }
    }

    @Test fun enterKeyEventsUseTheSameContinuationInSourceMode() = keyEventInput(EditorMode.SOURCE)
    @Test fun enterKeyEventsUseTheSameContinuationInLiveMode() = keyEventInput(EditorMode.LIVE)

    @Test fun crlfPastedIntoAnEmptyNoteKeepsItsNewlineOnEnterAndUndo() {
        val session = EditorSession("")
        val connection = input(session)
        compose.runOnIdle { connection.commitText("前文\r\n1. > 中文😀", 1) }
        compose.waitForIdle()
        compose.runOnIdle { connection.commitText("\n", 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("前文\r\n1. > 中文😀\r\n2. > ", session.content.source)
            session.undo(); assertEquals("前文\r\n1. > 中文😀", session.content.source)
        }
    }

    private fun keyEventInput(mode: EditorMode) {
        val session = EditorSession("1. > 中文😀")
        session.edit { selection = TextRange(length) }
        val connection = input(session, mode)
        compose.runOnIdle {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("1. > 中文😀\n2. > ", session.content.source)
            session.undo(); assertEquals("1. > 中文😀", session.content.source)
        }
    }
}
