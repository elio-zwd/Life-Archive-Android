package com.elio.lifearchive.core.document

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.*

internal data class InlineSpan(val start: Int, val end: Int, val openingEnd: Int, val closingStart: Int, val style: InlineStyle)
private fun InlineStyle.marker() = when (this) {
    InlineStyle.BOLD -> "**"
    InlineStyle.ITALIC -> "*"
    InlineStyle.STRIKE -> "~~"
    InlineStyle.HIGHLIGHT -> "=="
    InlineStyle.CODE -> "`"
}
internal fun inlineSpans(context: EditingContext): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    context.walk { node ->
        val style = when (node) {
            is StrongEmphasis -> InlineStyle.BOLD
            is Emphasis -> InlineStyle.ITALIC
            is Strikethrough -> InlineStyle.STRIKE
            is Code -> InlineStyle.CODE
            else -> null
        }
        if (style != null) context.range(node)?.let { range ->
            val raw = context.source.substring(range)
            val length = if (style == InlineStyle.CODE) raw.takeWhile { it == '`' }.length else if (style == InlineStyle.ITALIC) 1 else 2
            if (range.last + 1 - range.first >= length * 2) {
                val body = raw.substring(length, raw.length - length)
                val padding = if (style == InlineStyle.CODE && body.startsWith(' ') && body.endsWith(' ') && body.isNotBlank()) 1 else 0
                spans += InlineSpan(range.first, range.last + 1, range.first + length + padding, range.last + 1 - length - padding, style)
            }
        }
    }
    val masked = DocumentAnalyzer.masked(context.source)
    Regex("(?<![=\\\\])==(?=\\S)(.+?)(?<=\\S)==(?![=])").findAll(masked).forEach { match ->
        spans += InlineSpan(match.range.first, match.range.last + 1, match.range.first + 2, match.range.last - 1, InlineStyle.HIGHLIGHT)
    }
    return spans
}

internal fun clearInlineEdit(context: EditingContext, selection: EditSelection): MarkdownEdit {
    val source = context.source
    fun rejected() = MarkdownEdit(emptyList(), selection, "请完整选择单行的简单格式；复杂或不完整的标记保留原样")
    if (selection.collapsed || source.substring(selection.min, selection.max).any { it == '\n' || it == '\r' }) return rejected()

    val spans = inlineSpans(context).filter { it.start < selection.max && it.end > selection.min }.sortedBy { it.start }
    if (spans.isEmpty()) return rejected()
    if (spans.zipWithNext().any { (left, right) -> left.end > right.start }) return rejected()

    val masked = DocumentAnalyzer.masked(source, includeMath = true)
    val delimiters = mutableListOf<IntRange>()
    val syntax = "*_~=`\\[]<>\$"
    for (span in spans) {
        if (span.style == InlineStyle.CODE || source.substring(span.start, span.end).any { it == '\n' || it == '\r' }) return rejected()
        val complete = span.start >= selection.min && span.end <= selection.max
        val adjacent = span.openingEnd == selection.min && span.closingStart == selection.max
        if (!complete && !adjacent) return rejected()
        val opening = source.substring(span.start, span.openingEnd)
        val closing = source.substring(span.closingStart, span.end)
        if (opening != closing || opening !in setOf("**", "__", "*", "_", "~~", "==")) return rejected()
        // 移除标记后，紧邻的其他标记可能改变其左右定界规则。
        if (source.getOrNull(span.start - 1)?.let { it in syntax } == true || source.getOrNull(span.end)?.let { it in syntax } == true) return rejected()
        if (source.substring(span.start, span.end) != masked.substring(span.start, span.end)) return rejected()
        val line = editLine(source, span.start)
        val wikiOpen = source.lastIndexOf("[[", span.start).takeIf { it >= line.start }
        val wikiClose = source.indexOf("]]", span.end).takeIf { it >= 0 && it < line.end }
        if (wikiOpen != null && wikiClose != null) return rejected()
        delimiters += span.start until span.openingEnd
        delimiters += span.closingStart until span.end
    }

    var complex = false
    context.walk { node ->
        if (node is Code || node is Link || node is Image || node is HtmlInline) {
            context.range(node)?.let { range ->
                if (range.first < selection.max && range.last + 1 > selection.min) complex = true
            }
        }
    }
    if (complex) return rejected()
    // 未识别的符号可能属于链接、转义或嵌套语法，不猜测它们是否为普通文字。
    if ((selection.min until selection.max).any { index -> source[index] in syntax && delimiters.none { index in it } }) return rejected()
    val patches = delimiters.map { SourceEdit(it.first, it.last + 1, "") }
    val change = result(source, selection, patches)
    // 例如 **# 标题** 去掉标记后会成为标题；不能让行内命令改变块结构。
    fun blockShape(root: Node): List<String> = buildList {
        fun visit(node: Node) {
            if (node is Block) add("${node.javaClass.name}:${(node as? Heading)?.level}")
            var child = node.firstChild
            while (child != null) { visit(child); child = child.next }
            if (node is Block) add("/")
        }
        visit(root)
    }
    if (blockShape(context.root) != blockShape(EditingContext(change.apply(source)).root)) return rejected()
    return change
}

internal fun inlineEdit(context: EditingContext, selection: EditSelection, style: InlineStyle): MarkdownEdit {
    val source = context.source; val spans = inlineSpans(context)
    fun rejected() = MarkdownEdit(emptyList(), selection, "选区跨越了无法安全拆分的格式标记，请调整选区")
    if (selection.collapsed) {
        val enclosing = spans.filter { it.style == style && selection.min in it.openingEnd..it.closingStart }.minByOrNull { it.end - it.start }
        if (enclosing != null) return result(source, selection, listOf(SourceEdit(enclosing.start, enclosing.openingEnd, ""), SourceEdit(enclosing.closingStart, enclosing.end, "")))
        if (style != InlineStyle.CODE && spans.any { it.style == InlineStyle.CODE && selection.min in it.start until it.end }) return rejected()
        val marker = style.marker()
        return result(source, selection, listOf(SourceEdit(selection.min, selection.min, marker + marker)), EditSelection(selection.min + marker.length))
    }
    val patches = mutableListOf<SourceEdit>()
    val positions = mutableListOf<(Int, Boolean) -> Int>()
    // 行内工具不能把段落分隔包进一对标记，也不修改未选中的空白。
    editLines(source, selection).forEach { line ->
        if (headingLine(source, line) != line) return@forEach
        var a = maxOf(line.start, selection.min); var b = minOf(line.end, selection.max)
        val heading = heading(source, line)
        a = maxOf(a, heading.markerEnd)
        heading.closing?.let { b = minOf(b, it.first) }
        while (a < b && source[a].isWhitespace()) a++
        while (b > a && source[b - 1].isWhitespace()) b--
        if (a == b) return@forEach
        val matching = spans.filter { it.style == style && it.start < b && it.end > a }
        if (matching.any { it.start < line.start || it.end > line.end }) return rejected()
        if (spans.any { it.style == InlineStyle.CODE && style != InlineStyle.CODE && it.start < b && it.end > a }) return rejected()
        if (matching.any { a in (it.start + 1) until it.openingEnd || b in (it.closingStart + 1) until it.end }) return rejected()
        val remove = (a until b).all { at -> source[at].isWhitespace() || matching.any { at in it.start until it.end } }
        val regionStart = minOf(a, matching.minOfOrNull { it.start } ?: a)
        val regionEnd = maxOf(b, matching.maxOfOrNull { it.end } ?: b)
        val delimiters = matching.flatMap { listOf(it.start until it.openingEnd, it.closingStart until it.end) }.distinct().sortedBy { it.first }
        if (delimiters.zipWithNext().any { (x, y) -> x.last >= y.first }) return rejected()
        fun offset(at: Int): Int = at - regionStart - delimiters.sumOf { (at - it.first).coerceIn(0, it.last + 1 - it.first) }
        val base = buildString {
            var cursor = regionStart
            delimiters.forEach { append(source, cursor, it.first); cursor = it.last + 1 }
            append(source, cursor, regionEnd)
        }
        var cutStart = a; var cutEnd = b
        val others = spans.filter { it.style != style && it.start >= regionStart && it.end <= regionEnd }
        if (remove) others.forEach { span ->
            if (a == span.openingEnd) cutStart = minOf(cutStart, span.start)
            if (b == span.closingStart) cutEnd = maxOf(cutEnd, span.end)
        }
        val left = offset(cutStart); val right = offset(cutEnd)
        val ranges = matching.map { offset(it.openingEnd) until offset(it.closingStart) }.flatMap { range ->
            if (!remove) listOf(range) else buildList {
                if (range.first < left) add(range.first until minOf(left, range.last + 1))
                if (range.last + 1 > right) add(maxOf(right, range.first) until range.last + 1)
            }
        }.toMutableList()
        if (!remove) ranges += left until right
        val merged = mutableListOf<IntRange>()
        ranges.filter { !it.isEmpty() }.sortedBy { it.first }.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            else merged += range
        }
        val boundaries = merged.flatMap { listOf(it.first, it.last + 1) }
        if (others.any { other -> boundaries.any { it > offset(other.openingEnd) && it < offset(other.closingStart) } }) return rejected()
        val decorations = mutableListOf<Triple<IntRange, Int, Int>>()
        val replacement = buildString {
            var cursor = 0
            merged.forEach { range ->
                append(base, cursor, range.first)
                val content = base.substring(range)
                val marker = if (style == InlineStyle.CODE) "`".repeat((Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0) + 1) else style.marker()
                val pad = if (style == InlineStyle.CODE && (content.startsWith('`') || content.endsWith('`') || content.startsWith(' ') && content.endsWith(' '))) " " else ""
                decorations += Triple(range, marker.length + pad.length, marker.length + pad.length)
                append(marker); append(pad); append(content); append(pad); append(marker); cursor = range.last + 1
            }
            append(base, cursor, base.length)
        }
        patches += SourceEdit(regionStart, regionEnd, replacement)
        positions += { at, end ->
            val baseOffset = offset(at)
            baseOffset + decorations.sumOf { (range, open, close) ->
                (if (baseOffset > range.first || baseOffset == range.first && !end) open else 0) +
                    (if (baseOffset > range.last + 1 || baseOffset == range.last + 1 && !end) close else 0)
            }
        }
    }
    // 使用同一组区间映射选区，不靠搜索重复文字或猜测标记长度定位。
    fun mapped(at: Int, end: Boolean): Int {
        var delta = 0
        patches.forEachIndexed { index, patch ->
            if (at < patch.start) return at + delta
            if (at <= patch.end) return patch.start + delta + positions[index](at, end)
            delta += patch.replacement.length - (patch.end - patch.start)
        }
        return at + delta
    }
    val a = mapped(selection.min, false); val b = mapped(selection.max, true)
    return result(source, selection, patches, if (selection.start <= selection.end) EditSelection(a, b) else EditSelection(b, a))
}
