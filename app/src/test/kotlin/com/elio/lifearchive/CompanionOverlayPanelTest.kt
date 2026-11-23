package com.elio.lifearchive

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.designsystem.colors
import androidx.compose.ui.graphics.toArgb
import com.elio.lifearchive.domain.pet.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w432dp-h960dp-mdpi")
class CompanionOverlayPanelTest {
    @Test fun idleWindowContainsOnlyTheTransparentRole() {
        val panel = CompanionOverlayPanel(ApplicationProvider.getApplicationContext())
        assertEquals(1, panel.childCount)
        assertSame(panel.model, panel.getChildAt(0))
        assertNull(panel.background)
        assertFalse(panel.model.isOpaque)
        panel.release()
    }
    @Test fun reopeningChatRestoresDraftWithoutWritingItBackAndKeepsFullReply() {
        val state = CompanionState(profile = CompanionProfile(draft = "从伙伴页继续"), messages = listOf(
            CompanionMessage("reply", "op", MessageRole.COMPANION, "长回复".repeat(200), ReplyStatus.COMPLETE, 0)))
        val first = CompanionOverlayChat(ApplicationProvider.getApplicationContext())
        val edits = mutableListOf<String>()
        first.onDraft = { edits += it }
        first.render(state)
        assertTrue(edits.isEmpty())
        assertEquals(state.messages.single().text, first.reply.text.toString())
        first.input.setText("窗口中继续写")
        assertEquals(listOf("窗口中继续写"), edits)
        first.render(state, updateDraft = false)
        assertEquals("窗口中继续写", first.input.text.toString())
        first.release()
        val reopened = CompanionOverlayChat(ApplicationProvider.getApplicationContext())
        reopened.render(state.copy(profile = state.profile.copy(draft = "窗口中继续写")))
        assertEquals("窗口中继续写", reopened.input.text.toString())
        reopened.release()
    }
    @Test fun shortChatUsesGlobalPaletteAndKeepsControlsWithinBounds() {
        val panel = CompanionOverlayChat(ApplicationProvider.getApplicationContext())
        panel.palette(DocumentPalette.GRAPHITE)
        panel.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.AT_MOST))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        assertTrue(panel.height <= 260)
        val controls = panel.send.parent as View
        assertTrue(controls.bottom <= panel.height)
        assertEquals(DocumentPalette.GRAPHITE.colors.ink.toArgb(), panel.input.currentTextColor)
        panel.release()
    }
    @Test fun resizeNeverPlacesTheRoleOutsideUsableBounds() {
        val bounds = Rect(12, 28, 420, 700)
        assertEquals(OverlayPlacement(100, 28), overlayPlacement(999, -90, 320, 400, bounds, true))
        assertEquals(OverlayPlacement(12, 400), overlayPlacement(16, 400, 212, 200, bounds, true))
        assertEquals(OverlayPlacement(12, 28), overlayPlacement(-20, 500, 600, 900, bounds, false))
        assertEquals(Rect(12, 28, 420, 700), bounds)
    }
    @Test fun repeatedWindowMeasureDoesNotShrinkTheChatOrMoveItsControls() {
        val chat = CompanionOverlayChat(ApplicationProvider.getApplicationContext())
        val width = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
        chat.measure(width, View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        val height = chat.measuredHeight
        repeat(5) {
            chat.measure(width, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            chat.layout(0, 0, chat.measuredWidth, chat.measuredHeight)
            assertEquals(height, chat.measuredHeight)
            assertTrue((chat.send.parent as View).bottom <= chat.height)
        }
    }
}
