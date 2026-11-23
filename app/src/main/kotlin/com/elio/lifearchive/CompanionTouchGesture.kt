package com.elio.lifearchive

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/** 原地长按才能搬动；一旦开始抚摸，本次手势就不能升级成拖动。 */
internal class CompanionTouchGesture(
    private val view: View,
    private val feedback: () -> Unit,
    private val stroke: (Float, Float) -> Unit,
    private val tap: (Float) -> Unit,
    private val drag: (Float, Float) -> Unit,
    private val finishDrag: () -> Unit,
    private val menu: () -> Unit,
    private val interaction: () -> Unit,
) : View.OnTouchListener {
    private enum class Mode { IDLE, PRESS, STROKE, ARMED, DRAG }
    private var mode = Mode.IDLE
    private var x = 0f; private var y = 0f
    private var pressedAt = 0L
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val arm = Runnable { if (mode == Mode.PRESS) { mode = Mode.ARMED; feedback() } }
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel(); interaction(); x = event.rawX; y = event.rawY; pressedAt = event.eventTime; mode = Mode.PRESS
                handler.postDelayed(arm, 1_000)
            }
            MotionEvent.ACTION_MOVE -> {
                armFromEventTime(event)
                val dx = event.rawX - x; val dy = event.rawY - y
                if (hypot(dx, dy) > slop) {
                    if (mode == Mode.PRESS) { mode = Mode.STROKE; handler.removeCallbacks(arm) }
                    else if (mode == Mode.ARMED) mode = Mode.DRAG
                }
                when (mode) {
                    Mode.STROKE -> stroke(event.x, event.y)
                    Mode.DRAG -> drag(dx, dy)
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                armFromEventTime(event)
                when (mode) {
                    Mode.PRESS -> tap(event.y)
                    Mode.ARMED -> menu()
                    Mode.DRAG -> finishDrag()
                    else -> Unit
                }
                cancel()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAG) finishDrag()
                cancel()
            }
        }
        return true
    }
    private fun armFromEventTime(event: MotionEvent) {
        // 绘制或系统负载可能延迟 Handler；手势分类仍以实际按住时长为准。
        if (mode == Mode.PRESS && event.eventTime - pressedAt >= 1_000) {
            handler.removeCallbacks(arm); mode = Mode.ARMED; feedback()
        }
    }
    fun cancel() { handler.removeCallbacks(arm); mode = Mode.IDLE; stroke(Float.NaN, Float.NaN) }
}
