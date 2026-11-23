package com.elio.lifearchive

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionTouchGestureTest {
    private class Fixture {
        val view = View(ApplicationProvider.getApplicationContext())
        var taps = 0; var strokes = 0; var armed = 0; var menus = 0; var saves = 0
        val moves = mutableListOf<Pair<Float, Float>>()
        val gesture = CompanionTouchGesture(view, { armed++ }, { x, _ -> if (!x.isNaN()) strokes++ }, { taps++ },
            { x, y -> moves += x to y }, { saves++ }, { menus++ }, {})
        fun event(action: Int, x: Float = 10f, y: Float = 10f) {
            val e = MotionEvent.obtain(0, 0, action, x, y, 0)
            try { gesture.onTouch(view, e) } finally { e.recycle() }
        }
        fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    }
    @Test fun movingBeforeOneSecondRemainsStrokeEvenAfterWaiting() {
        val f = Fixture(); f.event(MotionEvent.ACTION_DOWN); f.advance(999)
        f.event(MotionEvent.ACTION_MOVE, 200f); f.advance(1500); f.event(MotionEvent.ACTION_MOVE, 250f); f.event(MotionEvent.ACTION_UP, 250f)
        assertEquals(2, f.strokes); assertEquals(0, f.armed); assertTrue(f.moves.isEmpty()); assertEquals(0, f.menus); assertEquals(0, f.taps)
    }
    @Test fun stationaryHoldOpensMenuButMovingAfterHoldDragsWithoutTap() {
        val f = Fixture(); f.event(MotionEvent.ACTION_DOWN); f.advance(1000); f.event(MotionEvent.ACTION_UP)
        assertEquals(1, f.armed); assertEquals(1, f.menus)
        f.event(MotionEvent.ACTION_DOWN); f.advance(1000); f.event(MotionEvent.ACTION_MOVE, 90f, 50f); f.event(MotionEvent.ACTION_UP, 90f, 50f)
        assertEquals(listOf(80f to 40f), f.moves); assertEquals(1, f.saves); assertEquals(1, f.menus); assertEquals(0, f.taps)
    }
    @Test fun cancelledPressNeverOpensMenuAndShortPressIsATap() {
        val f = Fixture(); f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_CANCEL); f.advance(2000)
        assertEquals(0, f.armed); assertEquals(0, f.menus)
        f.event(MotionEvent.ACTION_DOWN); f.advance(200); f.event(MotionEvent.ACTION_UP)
        assertEquals(1, f.taps)
    }
    @Test fun eventTimeRecognizesLongHoldEvenWhenMainTimerHasNotRun() {
        val f = Fixture()
        val down = MotionEvent.obtain(0, 10, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val up = MotionEvent.obtain(0, 1100, MotionEvent.ACTION_UP, 10f, 10f, 0)
        try { f.gesture.onTouch(f.view, down); f.gesture.onTouch(f.view, up) }
        finally { down.recycle(); up.recycle() }
        assertEquals(1, f.menus); assertEquals(0, f.taps)
    }
}
