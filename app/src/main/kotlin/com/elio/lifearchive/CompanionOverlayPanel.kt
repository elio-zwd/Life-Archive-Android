package com.elio.lifearchive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.designsystem.colors
import com.elio.lifearchive.core.live2d.Live2DView
import com.elio.lifearchive.domain.pet.*

/** 系统窗口的两种呈现；业务动作由服务传入，不持有另一套会话或凭据。 */
internal class CompanionOverlayPanel(context: Context) : LinearLayout(context) {
    val handle = Button(context).apply { contentDescription = "拖动或展开伙伴" }
    val model = Live2DView(context)
    val input = EditText(context).apply {
        hint = "和伙伴说说话"; minLines = 1; maxLines = 3
        filters = arrayOf(android.text.InputFilter.LengthFilter(16_000))
    }
    val send = Button(context).apply { text = "发送" }
    val open = Button(context).apply { text = "打开" }
    val close = Button(context).apply { text = "关闭" }
    val idleChat = Button(context).apply { text = "对话" }
    val idleHide = Button(context).apply { text = "收起" }
    private val bubble = TextView(context).apply { text = "慢慢来，我在这里。"; setPadding(dp(10), dp(8), dp(10), dp(8)) }
    private val modelStatus = TextView(context).apply { visibility = View.GONE; maxLines = 2 }
    private val stage = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
    private val replyScroll = ScrollView(context).apply { addView(bubble) }
    private val editor = LinearLayout(context).apply { orientation = VERTICAL }
    private val editorScroll = ScrollView(context).apply { isFillViewport = false; addView(editor) }
    private val idleControls = LinearLayout(context)
    var onDraft: (String) -> Unit = {}
    private var updating = false
    private val visibleFrame = Rect()
    var expanded = false
        private set

    init {
        orientation = VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
        addView(handle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stage.addView(replyScroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        stage.addView(model, LayoutParams(dp(80), dp(176)))
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(modelStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        editor.addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val controls = LinearLayout(context)
        listOf(send, open, close).forEach { controls.addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
        editor.addView(controls)
        addView(editorScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        idleControls.addView(idleChat, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        idleControls.addView(idleHide, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(idleControls)
        idleChat.setOnClickListener { handle.performClick() }
        idleHide.setOnClickListener { close.performClick() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!updating) onDraft(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        model.onTap = { bubble.text = "嗯，我在。点击上方可以继续聊。" }
        model.onStatus = { modelStatus.text = it; modelStatus.visibility = if (it == null) View.GONE else View.VISIBLE }
        setExpanded(false)
        palette(DocumentPalette.BLUE)
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        handle.text = if (value) "贴边对话 · 收起" else "拖动伙伴"
        editorScroll.visibility = if (value) View.VISIBLE else View.GONE
        idleControls.visibility = if (value) View.GONE else View.VISIBLE
        model.layoutParams.width = dp(if (value) 112 else 80)
        bubble.maxLines = if (value) Int.MAX_VALUE else 3
        bubble.ellipsize = if (value) null else android.text.TextUtils.TruncateAt.END
        if (!value) input.clearFocus()
        requestLayout()
    }

    fun render(state: CompanionState, updateDraft: Boolean = true, updateBubble: Boolean = true) {
        val last = state.messages.lastOrNull { it.role == MessageRole.COMPANION }
        if (updateBubble) bubble.text = if (state.generating) "正在想怎么回应你…" else last?.error ?: last?.text ?: "慢慢来，我在这里。"
        val draft = state.profile.draft
        if (updateDraft && input.text.toString() != draft) {
            updating = true
            input.setText(draft); input.setSelection(draft.length)
            updating = false
        }
        send.text = if (state.generating) "停止" else "发送"
        model.setCue("${last?.id}:${last?.status}:${last?.emotion}", if (state.generating) "THINKING" else last?.emotion?.name ?: "CALM")
    }

    fun failure(message: String) { bubble.text = message }

    fun palette(palette: DocumentPalette) {
        val colors = palette.colors
        val ink = colors.ink.toArgb(); val accent = colors.accent.toArgb()
        bubble.setTextColor(ink); input.setTextColor(ink); input.setHintTextColor(colors.muted.toArgb())
        modelStatus.setTextColor(colors.muted.toArgb())
        bubble.background = GradientDrawable().apply { setColor(colors.background.toArgb()); cornerRadius = dp(18).toFloat(); setStroke(dp(1), colors.line.toArgb()) }
        editor.background = GradientDrawable().apply { setColor(colors.background.toArgb()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), colors.line.toArgb()) }
        editor.setPadding(dp(8), dp(8), dp(8), dp(4))
        listOf(handle, send, open, close, idleChat, idleHide).forEach { it.setTextColor(accent); it.backgroundTintList = ColorStateList.valueOf(colors.soft.toArgb()) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 键盘与分屏会缩小可见区域；优先保留可滚动的输入和动作，不把底部控件推到屏外。
        getWindowVisibleDisplayFrame(visibleFrame)
        val height = minOf(resources.displayMetrics.heightPixels, visibleFrame.height().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels,
            View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: Int.MAX_VALUE)
        model.layoutParams.height = minOf(dp(if (expanded) 168 else 176), (height * if (expanded) 0.35f else 0.5f).toInt())
        replyScroll.layoutParams.height = model.layoutParams.height
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
    }

    fun release() { model.setActive(false); model.onStatus = {}; model.onTap = {}; onDraft = {} }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal data class OverlayPlacement(val x: Int, val y: Int)
internal fun overlayPlacement(x: Int, y: Int, width: Int, height: Int, bounds: Rect, dock: Boolean): OverlayPlacement {
    val right = (bounds.right - width).coerceAtLeast(bounds.left)
    val bottom = (bounds.bottom - height).coerceAtLeast(bounds.top)
    val safeX = x.coerceIn(bounds.left, right)
    return OverlayPlacement(if (!dock) safeX else if (safeX - bounds.left < right - safeX) bounds.left else right, y.coerceIn(bounds.top, bottom))
}
