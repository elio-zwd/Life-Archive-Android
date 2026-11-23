package com.elio.lifearchive

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.designsystem.colors
import com.elio.lifearchive.core.live2d.Live2DView
import com.elio.lifearchive.domain.pet.*

/** 常驻窗口只承载角色；文字与操作使用独立、短暂的系统窗口。 */
internal class CompanionOverlayPanel(context: Context) : FrameLayout(context) {
    val model = Live2DView(context)
    init { model.setCompactFraming(true); addView(model, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    fun release() {
        model.setActive(false)
        model.onStatus = {}
        model.onTap = {}
        model.onModelLoaded = {}
        model.setOnTouchListener(null)
    }
}

internal class CompanionOverlayChat(context: Context) : LinearLayout(context) {
    val input = EditText(context).apply {
        hint = "和伙伴说说话"; minLines = 1; maxLines = 3; textSize = 15f
        filters = arrayOf(android.text.InputFilter.LengthFilter(16_000))
        contentDescription = "桌面聊天输入"
    }
    val send = Button(context).apply { text = "发送" }
    val open = Button(context).apply { text = "完整聊天" }
    val collapse = Button(context).apply { text = "收起" }
    val reply = TextView(context).apply { textSize = 15f; setPadding(dp(4), dp(4), dp(4), dp(8)) }
    private val scroll = ScrollView(context).apply { addView(reply) }
    var onDraft: (String) -> Unit = {}
    private var updating = false
    init {
        orientation = VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(8))
        addView(TextView(context).apply { text = "和伙伴聊聊"; textSize = 13f }, LayoutParams(-1, dp(28)))
        addView(scroll, LayoutParams(-1, dp(112)))
        addView(input, LayoutParams(-1, -2))
        val controls = LinearLayout(context)
        listOf(open, collapse, send).forEach { controls.addView(it, LayoutParams(0, -2, 1f)) }
        addView(controls)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!updating) onDraft(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        palette(DocumentPalette.BLUE)
    }
    fun render(state: CompanionState, updateDraft: Boolean = true, updateBubble: Boolean = true) {
        val last = state.messages.lastOrNull { it.role == MessageRole.COMPANION }
        if (updateBubble) reply.text = if (state.generating) last?.text?.ifBlank { "正在想怎么回应你…" } else last?.error ?: last?.text ?: "想说什么都可以。"
        if (updateDraft && input.text.toString() != state.profile.draft) {
            updating = true; input.setText(state.profile.draft); input.setSelection(input.length()); updating = false
        }
        send.text = if (state.generating) "停止" else "发送"
    }
    fun failure(message: String) { reply.text = message }
    fun palette(palette: DocumentPalette) {
        val colors = palette.colors
        background = GradientDrawable().apply { setColor(colors.background.toArgb()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), colors.line.toArgb()) }
        reply.setTextColor(colors.ink.toArgb()); input.setTextColor(colors.ink.toArgb()); input.setHintTextColor(colors.muted.toArgb())
        (getChildAt(0) as TextView).setTextColor(colors.muted.toArgb())
        listOf(send, open, collapse).forEach { it.setTextColor(colors.accent.toArgb()); it.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.soft.toArgb()) }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val childWidth = View.MeasureSpec.makeMeasureSpec((View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        val unrestricted = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val controls = getChildAt(3)
        controls.measure(childWidth, unrestricted)
        // 不能用窗口自身高度的比例反算回复区；否则每次系统重测都会继续缩短并移动窗口。
        input.maxHeight = (limit - paddingTop - paddingBottom - dp(28) - controls.measuredHeight).coerceAtLeast(dp(32))
        input.measure(childWidth, unrestricted)
        val fixed = paddingTop + paddingBottom + dp(28) + input.measuredHeight + controls.measuredHeight
        scroll.layoutParams.height = minOf(dp(112), (limit - fixed).coerceAtLeast(0))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    fun release() { onDraft = {} }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal data class OverlayPlacement(val x: Int, val y: Int)
internal data class OverlayWindowBounds(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun overlayPlacement(x: Int, y: Int, width: Int, height: Int, bounds: Rect, dock: Boolean): OverlayPlacement {
    val right = (bounds.right - width).coerceAtLeast(bounds.left)
    val bottom = (bounds.bottom - height).coerceAtLeast(bounds.top)
    val safeX = x.coerceIn(bounds.left, right)
    return OverlayPlacement(if (!dock) safeX else if (safeX - bounds.left < right - safeX) bounds.left else right, y.coerceIn(bounds.top, bottom))
}

/** 根据模型真实画布比例与穿透状态计算悬浮窗位置与尺寸；穿透时横向铺满屏幕以便完整呈现横向特效与走动。 */
internal fun overlayWindowLayout(
    bounds: Rect,
    settings: OverlaySettings,
    modelAspect: Float,
    passthrough: Boolean,
    minHeightPx: Int,
): OverlayWindowBounds {
    val safeAspect = modelAspect.takeIf { it.isFinite() }?.coerceIn(0.55f, 1.80f) ?: 0.75f
    val height = (bounds.height() * settings.heightFraction).toInt().coerceIn(minHeightPx.coerceAtMost(bounds.height()), bounds.height())
    val width = if (passthrough) bounds.width()
    else (height * safeAspect).toInt().coerceIn((minHeightPx * 0.55f).toInt().coerceAtMost(bounds.width()), bounds.width())
    val x = if (passthrough) bounds.left else bounds.left + ((bounds.width() - width) * settings.xFraction).toInt()
    val y = bounds.top + ((bounds.height() - height) * settings.yFraction).toInt()
    return OverlayWindowBounds(x, y, width, height)
}
