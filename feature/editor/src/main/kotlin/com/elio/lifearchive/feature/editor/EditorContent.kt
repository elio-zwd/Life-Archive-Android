package com.elio.lifearchive.feature.editor

import androidx.compose.ui.text.TextRange

/** 同一正文修订只建立一次 String 快照；选区、组合范围和焦点不是正文修订。 */
data class EditorContentSnapshot(val revision: Long, val source: String)

internal data class EditorTextChange(val before: Long, val after: Long, val start: Int, val oldEnd: Int, val newEnd: Int) {
    val delta get() = newEnd - oldEnd
    fun forwardStart(at: Int): Int = when { at < start -> at; at >= oldEnd -> at + delta; else -> start }
    fun forwardEnd(at: Int): Int = when { at <= start -> at; at >= oldEnd -> at + delta; else -> newEnd }
    fun backwardStart(at: Int): Int = when { at < start -> at; at >= newEnd -> at - delta; else -> start }
    fun backwardEnd(at: Int): Int = when { at <= start -> at; at >= newEnd -> at - delta; else -> oldEnd }
}

/** 按起点排序的区间树，同时索引跨多行的大范围样式，避免仅二分起点漏掉外层引用。 */
internal class EditorRangeIndex<T>(ranges: List<T>, private val start: (T) -> Int, private val end: (T) -> Int) {
    private val values = ranges.sortedBy(start)
    private val maxEnd = IntArray(maxOf(1, values.size * 4))
    init { if (values.isNotEmpty()) build(1, 0, values.lastIndex) }
    private fun build(node: Int, left: Int, right: Int): Int {
        val result = if (left == right) end(values[left]) else {
            val middle = (left + right) ushr 1
            maxOf(build(node * 2, left, middle), build(node * 2 + 1, middle + 1, right))
        }
        maxEnd[node] = result
        return result
    }
    internal data class Query<T>(val values: List<T>, val visited: Int)
    fun query(from: Int, until: Int): Query<T> {
        val result = mutableListOf<T>(); var visited = 0
        fun visit(node: Int, left: Int, right: Int) {
            visited++
            if (maxEnd[node] <= from || start(values[left]) >= until) return
            if (left == right) { result += values[left]; return }
            val middle = (left + right) ushr 1
            visit(node * 2, left, middle); visit(node * 2 + 1, middle + 1, right)
        }
        if (values.isNotEmpty() && from < until) visit(1, 0, values.lastIndex)
        return Query(result, visited)
    }
}

internal class EditorDisplaySnapshot(val revision: Long, val document: ParsedDocument) {
    private val styles = EditorRangeIndex(document.styles, MarkRange::start, MarkRange::end)
    private val markers = EditorRangeIndex(document.markers, MarkerRange::start, MarkerRange::end)

    fun styles(window: IntRange, changes: List<EditorTextChange>, length: Int): List<MarkRange> {
        val range = originalWindow(window, changes)
        return styles.query(range.first, range.last + 1).values.mapNotNull { style ->
            var start = style.start; var end = style.end
            changes.forEach { start = it.forwardStart(start); end = it.forwardEnd(end) }
            start = start.coerceIn(0, length); end = end.coerceIn(0, length)
            if (start < end && end > window.first && start <= window.last) style.copy(start = start, end = end) else null
        }
    }

    fun markers(window: IntRange, changes: List<EditorTextChange>): List<MarkerRange> {
        val range = originalWindow(window, changes)
        return markers.query(range.first, range.last + 1).values.mapNotNull { marker ->
            var start = marker.start; var end = marker.end
            for (change in changes) when {
                end <= change.start -> Unit
                start >= change.oldEnd -> { start += change.delta; end += change.delta }
                else -> return@mapNotNull null
            }
            if (start >= window.first && end <= window.last) MarkerRange(start, end) else null
        }
    }

    private fun originalWindow(window: IntRange, changes: List<EditorTextChange>): IntRange {
        var start = window.first; var end = window.last
        changes.asReversed().forEach { start = it.backwardStart(start); end = it.backwardEnd(end) }
        return (start - 1).coerceAtLeast(0)..(end + 1).coerceAtMost(document.source.length)
    }
}

internal fun visibleMarkers(source: String, markers: List<MarkerRange>, selection: TextRange, composition: TextRange?): List<MarkerRange> {
    fun protected(range: TextRange): IntRange {
        val start = source.lastIndexOf('\n', (range.min - 1).coerceIn(-1, source.length - 1)) + 1
        val end = source.indexOf('\n', range.max.coerceAtMost(source.length)).let { if (it < 0) source.length else it }
        return start..end
    }
    val active = protected(selection); val composing = composition?.let(::protected)
    val result = mutableListOf<MarkerRange>()
    markers.forEach { marker ->
        if (marker.start !in active && marker.end - 1 !in active &&
            (composing == null || marker.start !in composing && marker.end - 1 !in composing) &&
            result.lastOrNull()?.end?.let { it > marker.start } != true) result += marker
    }
    return result
}
