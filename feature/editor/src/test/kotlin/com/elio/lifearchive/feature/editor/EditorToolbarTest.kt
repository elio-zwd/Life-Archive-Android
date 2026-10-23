package com.elio.lifearchive.feature.editor

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
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
class EditorToolbarTest {
    @get:Rule val compose = createComposeRule()
    private fun show(session: EditorSession, calls: MutableList<MarkdownCommand> = mutableListOf(), width: Int = 412): Long {
        var doubleTap = 0L
        compose.setContent {
            doubleTap = LocalViewConfiguration.current.doubleTapTimeoutMillis
            LifeArchiveTheme {
                Column(Modifier.requiredSize(width.dp, 700.dp)) {
                    Box(Modifier.weight(1f)) { ComposeEditorEngine.Content(session, EditorMode.SOURCE, MarkdownParser().parse(session.text.text.toString()), 1f, true, true) }
                    EditorToolbar(session, true, null, { command, anchor -> calls += command; session.command(command, anchor) }, {}, {})
                }
            }
        }
        compose.waitForIdle()
        return doubleTap
    }

    @Test fun doubleTapOnlyDecreasesOnceAndUndoRestoresTheOriginalHeading() {
        val session = EditorSession("## 标题"); val calls = mutableListOf<MarkdownCommand>()
        val timeout = show(session, calls)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("tool-heading").performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(timeout + 100)
        compose.runOnIdle {
            assertEquals(listOf(MarkdownCommand.AdjustHeading(-1)), calls)
            assertEquals("# 标题", session.text.text.toString())
            session.undo(); assertEquals("## 标题", session.text.text.toString())
        }
    }

    @Test fun separatedSingleTapsIncreaseAndTypingCancelsAnUndecidedTap() {
        val session = EditorSession("正文"); val calls = mutableListOf<MarkdownCommand>()
        val timeout = show(session, calls)
        compose.mainClock.autoAdvance = false
        repeat(2) {
            compose.onNodeWithTag("tool-heading").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(timeout + 100)
        }
        compose.runOnIdle { assertEquals("## 正文", session.text.text.toString()); assertEquals(2, calls.size) }
        compose.onNodeWithTag("tool-heading").performTouchInput { click() }
        compose.runOnUiThread { session.text.edit { append("新输入") } }
        compose.mainClock.advanceTimeBy(timeout + 100)
        compose.runOnIdle { assertEquals("## 正文新输入", session.text.text.toString()); assertEquals(2, calls.size) }
    }

    @Test fun longPressSelectsDirectlyWithoutChangingTextWhileMenuOpens() {
        val session = EditorSession("正文")
        show(session)
        compose.onNodeWithTag("tool-heading").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals("正文", session.text.text.toString()) }
        compose.onNodeWithText("H4").performClick()
        compose.runOnIdle { assertEquals("#### 正文", session.text.text.toString()) }
    }

    @Test fun panelsAreMutuallyExclusiveKeepTheSelectionAndKeyboardControlVisible() {
        val session = EditorSession("甲乙丙")
        show(session, width = 360)
        compose.runOnIdle { session.text.edit { selection = TextRange(1, 2) } }
        compose.onNodeWithTag("tool-format").performClick()
        compose.onNodeWithText("加粗").performClick()
        compose.runOnIdle { assertEquals("甲**乙**丙", session.text.text.toString()); assertEquals(TextRange(3, 4), session.text.selection) }
        compose.onNodeWithTag("editor-body").assertIsFocused()
        compose.onNodeWithTag("tool-list").performClick()
        compose.onAllNodesWithText("加粗").assertCountEquals(0)
        compose.onNodeWithText("无序列表").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起键盘").assertIsDisplayed()
    }
}
