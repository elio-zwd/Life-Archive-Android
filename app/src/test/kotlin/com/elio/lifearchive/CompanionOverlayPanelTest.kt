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
    @Test fun idleAndDialogueShareOneModelAndDraftWithoutProgrammaticFeedback() {
        val panel = CompanionOverlayPanel(ApplicationProvider.getApplicationContext())
        val model = panel.model
        val edits = mutableListOf<String>()
        panel.onDraft = { edits += it }
        assertEquals(View.GONE, panel.input.parent.let { (it as View).parent as View }.visibility)
        panel.setExpanded(true)
        panel.render(CompanionState(profile = CompanionProfile(draft = "从伙伴页继续")))
        assertTrue(edits.isEmpty())
        assertEquals("从伙伴页继续", panel.input.text.toString())
        panel.input.setText("窗口中继续写")
        assertEquals(listOf("窗口中继续写"), edits)
        panel.render(CompanionState(profile = CompanionProfile(draft = "旧回执")), updateDraft = false)
        assertEquals("窗口中继续写", panel.input.text.toString())
        panel.setExpanded(false); panel.setExpanded(true)
        assertSame(model, panel.model)
        assertEquals("窗口中继续写", panel.input.text.toString())
        var hides = 0
        panel.close.setOnClickListener { hides++ }
        panel.idleHide.performClick()
        assertEquals(1, hides)
        panel.release()
    }

    @Test fun shortWindowBoundsHeightAndUsesTheGlobalPalette() {
        val panel = CompanionOverlayPanel(ApplicationProvider.getApplicationContext())
        panel.setExpanded(true); panel.palette(DocumentPalette.GRAPHITE)
        panel.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.AT_MOST))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        assertTrue(panel.measuredHeight <= 260)
        assertTrue(panel.model.measuredHeight <= 91)
        assertEquals(DocumentPalette.GRAPHITE.colors.ink.toArgb(), panel.input.currentTextColor)
        panel.release()
    }

    @Test fun dockingAndResizeNeverPlaceTheHandleOutsideUsableBounds() {
        val bounds = Rect(12, 28, 420, 700)
        assertEquals(OverlayPlacement(100, 28), overlayPlacement(999, -90, 320, 400, bounds, true))
        assertEquals(OverlayPlacement(12, 400), overlayPlacement(16, 400, 212, 200, bounds, true))
        assertEquals(OverlayPlacement(12, 28), overlayPlacement(-20, 500, 600, 900, bounds, false))
        assertEquals(Rect(12, 28, 420, 700), bounds)
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun renderBothWindowModesForEachSharedPalette() {
        val panel = CompanionOverlayPanel(ApplicationProvider.getApplicationContext())
        panel.render(CompanionState(profile = CompanionProfile(draft = "今天想出去走走"), messages = listOf(
            CompanionMessage("preview", "preview", MessageRole.COMPANION, "慢慢来，我在这里。", ReplyStatus.COMPLETE, 0),
        )))
        val output = java.io.File(System.getProperty("java.io.tmpdir"), "life-archive-companion-previews").apply { mkdirs() }
        for (palette in DocumentPalette.entries) for (expanded in listOf(false, true)) {
            panel.palette(palette); panel.setExpanded(expanded)
            panel.measure(View.MeasureSpec.makeMeasureSpec(if (expanded) 320 else 212, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            val bitmap = android.graphics.Bitmap.createBitmap(panel.width, panel.height, android.graphics.Bitmap.Config.ARGB_8888)
            panel.draw(android.graphics.Canvas(bitmap))
            java.io.File(output, "system-${if (expanded) "dialogue" else "idle"}-${palette.name.lowercase()}.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            assertTrue(panel.height > 0)
        }
        panel.release()
    }
}
