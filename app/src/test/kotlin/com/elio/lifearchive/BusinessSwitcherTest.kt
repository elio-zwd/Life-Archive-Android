package com.elio.lifearchive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class BusinessSwitcherTest {
    @Test fun positionAvoidsMeasuredBottomBarAndSystemInsets() {
        val button = IntSize(100, 56)
        val bounds = SwitchBounds.create(1080, 2400, button, 0, 80, 0, 30, 168, 12)
        val point = bounds.absolute(SwitchPosition(1f, 1f))
        assertTrue(point.y + button.height <= 2400 - 168 - 12)
        assertTrue(point.x + button.width <= 1080 - 12)
        assertTrue(point.y >= 80 + 12)
        assertEquals(point, bounds.clamp(Offset(Float.MAX_VALUE, Float.MAX_VALUE)))
    }

    @Test fun normalizedPositionSurvivesWindowResize() {
        val portrait = SwitchBounds.create(1080, 2400, IntSize(100, 56), 0, 80, 0, 30, 168, 12)
        val saved = portrait.relative(portrait.absolute(SwitchPosition(.8f, .7f)))
        val landscape = SwitchBounds.create(2400, 1080, IntSize(100, 56), 30, 40, 30, 20, 150, 12)
        val restored = landscape.absolute(saved)
        assertEquals(saved.x, landscape.relative(restored).x, .0001f)
        assertEquals(saved.y, landscape.relative(restored).y, .0001f)
        assertEquals(restored, landscape.clamp(restored))
    }

    @Test fun invalidStoredCoordinatesHaveSafeDefaults() {
        assertEquals(SwitchPosition(), SwitchPosition(Float.NaN, Float.POSITIVE_INFINITY).normalized())
        assertEquals(SwitchPosition(0f, 1f), SwitchPosition(-9f, 9f).normalized())
    }
}
