package com.elio.lifearchive.feature.editor

/** 只映射显示范围，不修改 TextFieldState。解析尚未追上输入时保留未受影响的高亮。 */
data class EditorRenderProjection(val revision: Long, val document: ParsedDocument) {
    private fun change(current: String): Triple<Int, Int, Int> {
        val previous = document.source
        var prefix = 0
        while (prefix < minOf(previous.length, current.length) && previous[prefix] == current[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(previous.length, current.length) - prefix && previous[previous.lastIndex - suffix] == current[current.lastIndex - suffix]) suffix++
        return Triple(prefix, previous.length - suffix, current.length - suffix)
    }

    fun forText(current: String): ParsedDocument {
        val previous = document.source
        if (previous == current) return document
        val (prefix, oldEnd, newEnd) = change(current)
        val delta = current.length - previous.length
        fun start(at: Int) = when { at < prefix -> at; at >= oldEnd -> at + delta; else -> prefix }
        fun end(at: Int) = when { at <= prefix -> at; at >= oldEnd -> at + delta; else -> newEnd }
        val styles = document.styles.mapNotNull {
            val a = start(it.start).coerceIn(0, current.length); val b = end(it.end).coerceIn(0, current.length)
            if (a < b) it.copy(start = a, end = b) else null
        }
        val markers = document.markers.mapNotNull {
            when {
                it.end <= prefix -> it
                it.start >= oldEnd -> it.copy(start = it.start + delta, end = it.end + delta)
                else -> null // 不能隐藏正在修改的标记，也不猜测新标记。
            }
        }
        val blocks = document.blocks.mapNotNull {
            val a = start(it.start).coerceIn(0, current.length); val b = end(it.end).coerceIn(0, current.length)
            if (a < b) it.copy(start = a, end = b) else null
        }
        return document.copy(source = current, blocks = blocks, styles = styles, markers = markers)
    }

    /** 完整解析在后台追赶；先重算变更所在块和相邻行，不清除其他块的样式。 */
    fun reparseChangedBlock(current: String): ParsedDocument {
        if (document.source == current) return document
        val previous = document.source
        val (prefix, oldEnd, _) = change(current)
        var start = previous.lastIndexOf('\n', (prefix - 1).coerceAtLeast(-1)) + 1
        if (start > 0) start = previous.lastIndexOf('\n', (start - 2).coerceAtLeast(-1)) + 1
        var end = previous.indexOf('\n', oldEnd).let { if (it < 0) previous.length else it + 1 }
        if (end < previous.length) end = previous.indexOf('\n', end).let { if (it < 0) previous.length else it + 1 }
        val adjacent = document.blocks.filter { it.start < end && it.end > start }
        start = minOf(start, adjacent.minOfOrNull { it.start } ?: start)
        end = maxOf(end, adjacent.maxOfOrNull { it.end } ?: end)
        val mapped = forText(current)
        val currentEnd = (end + current.length - previous.length).coerceIn(start, current.length)
        // 巨型段落、代码块和跨块粘贴不在输入热路径内整段重算。
        if (maxOf(end - start, currentEnd - start) > 8192) return mapped
        val fragment = current.substring(start, currentEnd)
        if (start != 0 && fragment.startsWith("---")) return mapped
        val patch = MarkdownParser().parse(fragment)
        return mapped.copy(
            blocks = (mapped.blocks.filter { it.end <= start || it.start >= currentEnd } + patch.blocks.map { it.copy(start = it.start + start, end = it.end + start) }).sortedBy { it.start },
            styles = mapped.styles.filter { it.end <= start || it.start >= currentEnd } + patch.styles.map { it.copy(start = it.start + start, end = it.end + start) },
            markers = (mapped.markers.filter { it.end <= start || it.start >= currentEnd } + patch.markers.map { it.copy(start = it.start + start, end = it.end + start) }).distinct().sortedBy { it.start },
        )
    }
}

internal fun styleLines(text: CharSequence, start: Int, end: Int): List<IntRange> {
    val ranges = mutableListOf<IntRange>(); var line = start
    for (i in start until end) if (text[i] == '\r' || text[i] == '\n') { if (line < i) ranges += line until i; line = i + 1 }
    if (line < end) ranges += line until end
    return ranges
}
