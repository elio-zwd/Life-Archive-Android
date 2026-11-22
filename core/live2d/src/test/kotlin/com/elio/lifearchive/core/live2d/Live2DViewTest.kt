package com.elio.lifearchive.core.live2d

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Live2DViewTest {
    @Test fun viewConstructionIsTransparentAndDoesNotRequireNativeContext() {
        val view = Live2DView(ApplicationProvider.getApplicationContext())
        assertFalse(view.isOpaque)
        assertTrue(view.contentDescription.toString().contains("轻触"))
        view.setActive(false); view.setCue("same-reply", "UNTRUSTED_ACTION")
        assertFalse(view.isAvailable)
    }
    @Test fun accessibilityClickUsesSameTouchFeedbackCallback() {
        val view = Live2DView(ApplicationProvider.getApplicationContext())
        var clicks = 0
        view.onTap = { clicks++ }
        assertTrue(view.performClick()); assertEquals(1, clicks)
    }
}
