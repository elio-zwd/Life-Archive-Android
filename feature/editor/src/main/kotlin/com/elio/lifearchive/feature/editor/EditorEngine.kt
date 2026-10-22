package com.elio.lifearchive.feature.editor

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

interface EditorEngine {
    @Composable fun Content(session: EditorSession, mode: EditorMode, parsed: ParsedDocument?, fontScale: Float, requestFocus: Boolean, enabled: Boolean)
}

object ComposeEditorEngine : EditorEngine {
    @Composable override fun Content(session: EditorSession, mode: EditorMode, parsed: ParsedDocument?, fontScale: Float, requestFocus: Boolean, enabled: Boolean) {
        val colors = MaterialTheme.colorScheme
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val currentParsed by rememberUpdatedState(parsed)
        val transformation = remember(session, mode, colors, fontScale) {
            OutputTransformation {
                val base = currentParsed ?: return@OutputTransformation
                val parsed = EditorRenderProjection(0, base).forText(asCharSequence().toString())
                val selection = session.text.selection
                val composition = session.text.composition
                val window = previewWindow(parsed.source.length, selection.end)
                val hidden = if (mode == EditorMode.SOURCE) emptyList() else visibleMarkers(parsed, selection, composition).filter { it.start >= window.first && it.end <= window.last }
                hidden.asReversed().forEach { replace(it.start, it.end, "") }
                val removed = IntArray(hidden.size + 1)
                hidden.forEachIndexed { i, marker -> removed[i + 1] = removed[i] + marker.end - marker.start }
                // 长文不能为每段样式遍历全部隐藏标记，否则光标刷新会退化为平方复杂度。
                fun mapped(offset: Int): Int {
                    var low = 0; var high = hidden.size
                    while (low < high) { val middle = (low + high) ushr 1; if (hidden[middle].end <= offset) low = middle + 1 else high = middle }
                    val partial = hidden.getOrNull(low)?.let { (offset - it.start).coerceIn(0, it.end - it.start) } ?: 0
                    return offset - removed[low] - partial
                }
                parsed.styles.forEach { range ->
                    if (range.end < window.first || range.start > window.last) return@forEach
                    val style = when (range.mark) {
                        TextMark.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                        TextMark.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        TextMark.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        TextMark.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHighest)
                        TextMark.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (23 * fontScale).sp)
                        TextMark.LINK -> SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
                        TextMark.QUOTE -> SpanStyle(color = colors.onSurfaceVariant)
                        else -> SpanStyle()
                    }
                    val start = mapped(range.start.coerceAtLeast(window.first)); val end = mapped(range.end.coerceAtMost(window.last))
                    if (end > start && end <= length) styleLines(asCharSequence(), start, end).forEach { addStyle(style, it.first, it.last + 1) }
                }
            }
        }
        BasicTextField(
            state = session.text,
            enabled = enabled,
            modifier = Modifier.fillMaxSize().testTag("editor-body").focusRequester(focus).padding(horizontal = 22.dp, vertical = 16.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = colors.onSurface, fontSize = (17 * fontScale).sp, lineHeight = (29 * fontScale).sp,
                fontFamily = if (mode == EditorMode.SOURCE) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(colors.primary), outputTransformation = transformation, scrollState = session.scroll,
        )
        LaunchedEffect(requestFocus) { if (requestFocus) { focus.requestFocus(); keyboard?.show() } }
    }
}

internal const val FULL_PREVIEW_LIMIT = 128 * 1024

internal fun previewWindow(length: Int, cursor: Int): IntRange =
    if (length <= FULL_PREVIEW_LIMIT) 0..length
    else (cursor - 4096).coerceAtLeast(0)..(cursor + 4096).coerceAtMost(length)

/** 激活行及选区/组合输入跨越的行不隐藏标记，避免点击及输入时失去语法上下文。 */
internal fun visibleMarkers(parsed: ParsedDocument, selection: TextRange, composition: TextRange?): List<MarkerRange> {
    fun protected(range: TextRange): IntRange {
        val start = parsed.source.lastIndexOf('\n', (range.min - 1).coerceAtLeast(-1)) + 1
        val end = parsed.source.indexOf('\n', range.max.coerceAtMost(parsed.source.length)).let { if (it < 0) parsed.source.length else it }
        return start..end
    }
    val active = protected(selection)
    val composing = composition?.let(::protected)
    return parsed.markers.filter { marker ->
        marker.start !in active && marker.end - 1 !in active &&
            (composing == null || (marker.start !in composing && marker.end - 1 !in composing))
    }.fold(mutableListOf()) { result, marker ->
        if (result.lastOrNull()?.end?.let { it > marker.start } != true) result += marker
        result
    }
}
