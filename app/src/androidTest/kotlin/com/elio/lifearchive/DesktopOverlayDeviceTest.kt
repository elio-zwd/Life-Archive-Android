package com.elio.lifearchive

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅在明确指定参数且已开启桌面陪伴的隔离设备上运行；不清资料、不发送聊天。 */
@RunWith(AndroidJUnit4::class)
class DesktopOverlayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        return ui.windows.firstNotNullOfOrNull { it.root?.let(::walk) }
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) { find(predicate)?.let { return it }; SystemClock.sleep(100) }
        error("未找到预期的悬浮窗口控件")
    }
    private fun roleBounds(): Rect {
        val node = awaitNode { it.contentDescription?.startsWith("桌面伙伴") == true }
        node.refresh()
        return Rect().also { node.getBoundsInScreen(it) }
    }
    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(ui.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun hold(bounds: Rect, dx: Float = 0f, dy: Float = 0f) {
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY(); val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(1_150)
        if (dx != 0f || dy != 0f) repeat(10) { step ->
            event(down, MotionEvent.ACTION_MOVE, x + dx * (step + 1) / 10, y + dy * (step + 1) / 10); SystemClock.sleep(25)
        }
        event(down, MotionEvent.ACTION_UP, x + dx, y + dy); SystemClock.sleep(400)
    }
    private fun screenshot(name: String) {
        val bitmap = ui.takeScreenshot() ?: error("无法截图")
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "desktop-validation").apply { mkdirs() }
        try { File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    @Test fun strokeDragMenuAndKeyboardChatOperateWithoutChangingConversation() {
        assumeTrue("需显式传入 -e desktop-overlay true，并预先开启伙伴", InstrumentationRegistry.getArguments().getString("desktop-overlay") == "true")
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        assertTrue("先由用户授权悬浮窗权限", Settings.canDrawOverlays(instrumentation.targetContext))
        // 启动 instrumentation 会重建目标进程，因此在已授权测试中恢复其桌面服务。
        instrumentation.targetContext.startForegroundService(Intent(instrumentation.targetContext, CompanionOverlayService::class.java))
        ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        SystemClock.sleep(2_000)
        val original = roleBounds()
        var originalDraft: String? = null
        try {
            val x = original.exactCenterX(); val y = original.exactCenterY(); val down = SystemClock.uptimeMillis()
            event(down, MotionEvent.ACTION_DOWN, x, y)
            event(down, MotionEvent.ACTION_MOVE, x - 45, y); SystemClock.sleep(1_200)
            event(down, MotionEvent.ACTION_MOVE, x + 45, y); event(down, MotionEvent.ACTION_UP, x + 45, y)
            assertEquals("抚摸不能移动窗口", original, roleBounds())
            assertNull("抚摸不能误开菜单", find { it.contentDescription == "调整大小" })
            hold(original, -130f, -180f)
            val moved = roleBounds()
            assertTrue("长按后应能拖动", kotlin.math.abs(moved.left - original.left + 130) < 12 && kotlin.math.abs(moved.top - original.top + 180) < 12)
            screenshot("dragged.png")
            hold(moved, (original.left - moved.left).toFloat(), (original.top - moved.top).toFloat())
            val restored = roleBounds()
            assertTrue("恢复位置只允许像素取整误差", kotlin.math.abs(original.left - restored.left) <= 2 && kotlin.math.abs(original.top - restored.top) <= 2)
            hold(roleBounds())
            awaitNode { it.contentDescription == "聊天" }; screenshot("menu.png")
            SystemClock.sleep(8_500)
            assertNull("闲置菜单应自动消失", find { it.contentDescription == "聊天" })
            hold(roleBounds()); assertTrue(awaitNode { it.contentDescription == "聊天" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val input = awaitNode { it.contentDescription == "桌面聊天输入" }
            val previousDraft = input.text?.toString()
            originalDraft = if (input.isShowingHintText) "" else previousDraft.orEmpty()
            input.refresh()
            val inputBounds = Rect().also { input.getBoundsInScreen(it) }
            val closeBounds = Rect().also { awaitNode { node -> node.text == "收起" }.getBoundsInScreen(it) }
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "input=" + inputBounds + ", collapse=" + closeBounds + "\n") })
            screenshot("chat-before-keyboard.png")
            val inputDown = SystemClock.uptimeMillis()
            event(inputDown, MotionEvent.ACTION_DOWN, inputBounds.exactCenterX(), inputBounds.exactCenterY())
            event(inputDown, MotionEvent.ACTION_UP, inputBounds.exactCenterX(), inputBounds.exactCenterY())
            val keyboardDeadline = SystemClock.uptimeMillis() + 5_000
            while (ui.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } && SystemClock.uptimeMillis() < keyboardDeadline) SystemClock.sleep(100)
            if (InstrumentationRegistry.getArguments().getString("test-ime") == "true") awaitNode { it.contentDescription == "test-ime-input" && it.isVisibleToUser }
            SystemClock.sleep(650)
            screenshot("keyboard-chat.png")
            assertTrue(awaitNode { it.text == "发送" || it.text == "停止" }.isVisibleToUser)
            assertTrue("输入法应显示", ui.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            val ime = ui.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val imeBounds = Rect().also { ime.getBoundsInScreen(it) }
            assertTrue("键盘窗口不能只有零高度", imeBounds.height() > 100)
            val sendBounds = Rect().also { awaitNode { node -> node.text == "发送" || node.text == "停止" }.getBoundsInScreen(it) }
            assertTrue("发送按钮必须在键盘上方", sendBounds.bottom <= imeBounds.top)
            if (InstrumentationRegistry.getArguments().getString("test-ime") == "true") {
                awaitNode { it.contentDescription == "test-ime-input" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                assertTrue(awaitNode { it.contentDescription == "桌面聊天输入" && !it.isShowingHintText }.text.toString().contains("a"))
                awaitNode { it.contentDescription == "test-ime-erase" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                awaitNode { it.contentDescription == "桌面聊天输入" && (if (it.isShowingHintText) "" else it.text?.toString().orEmpty()) == originalDraft }
            }
            assertTrue(awaitNode { it.text == "收起" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(500)
            assertNull(find { it.contentDescription == "桌面聊天输入" })
            hold(roleBounds()); awaitNode { it.contentDescription == "聊天" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            assertEquals("往返不丢草稿", previousDraft, awaitNode { it.contentDescription == "桌面聊天输入" }.text?.toString())
            awaitNode { it.text == "收起" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            screenshot("role-only.png")
        } finally {
            originalDraft?.let { draft -> find { it.contentDescription == "桌面聊天输入" }?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft) }) }
            find { it.text == "收起" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val current = roleBounds()
            if (current != original) hold(current, (original.left - current.left).toFloat(), (original.top - current.top).toFloat())
        }
    }
}
