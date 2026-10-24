package com.elio.lifearchive.core.document

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

data class EditSelection(val start: Int, val end: Int = start) {
    val min get() = minOf(start, end)
    val max get() = maxOf(start, end)
    val collapsed get() = start == end
}
data class SourceEdit(val start: Int, val end: Int, val replacement: String)
data class MarkdownEdit(val edits: List<SourceEdit>, val selection: EditSelection, val error: String? = null) {
    fun apply(source: String): String = buildString {
        var cursor = 0
        edits.sortedBy { it.start }.forEach { edit ->
            require(edit.start >= cursor && edit.end in edit.start..source.length)
            append(source, cursor, edit.start); append(edit.replacement); cursor = edit.end
        }
        append(source, cursor, source.length)
    }
}
enum class ListStyle { BULLET, ORDERED, TASK }
enum class InlineStyle { BOLD, ITALIC, STRIKE, HIGHLIGHT, CODE }
enum class InsertTemplate { TABLE, CODE_BLOCK, DIVIDER, CALLOUT, INLINE_MATH, BLOCK_MATH, MERMAID, FOOTNOTE }
sealed interface MarkdownCommand {
    data class AdjustHeading(val delta: Int) : MarkdownCommand
    data class Heading(val level: Int) : MarkdownCommand
    data class List(val style: ListStyle) : MarkdownCommand
    data class Indent(val delta: Int) : MarkdownCommand
    data object ToggleTask : MarkdownCommand
    data object Quote : MarkdownCommand
    data class Inline(val style: InlineStyle) : MarkdownCommand
    data class Template(val template: InsertTemplate, val parameter: String = "") : MarkdownCommand
    data class InsertLink(val markup: String) : MarkdownCommand
}
data class MarkdownTools(val heading: Int?, val list: ListStyle?, val hasTask: Boolean, val canIndent: Boolean)

/** 命令只计算原文补丁；调用方核验会话后，一次性应用并记录撤销。 */
object MarkdownEditing {
    fun tools(source: String, selection: EditSelection, parsed: ParsedDocument? = null): MarkdownTools {
        val lines = editLines(source, selection).map { headingLine(source, it) }.distinctBy { it.start }.filter { it.start == it.end || source.substring(it.start, it.end).isNotBlank() }
        val headings = parsed?.takeIf { it.source == source }?.styles?.filter { it.mark == TextMark.HEADING }
        val levels = lines.map { line ->
            val range = headings?.firstOrNull { linePrefix(source, line).contentStart in it.start until it.end }
            if (range == null) heading(source, line).level else {
                var hashes = 0
                while (hashes < 6 && source.getOrNull(range.start + hashes) == '#') hashes++
                if (hashes > 0) hashes else {
                    val last = editLine(source, range.end - 1)
                    if (source.getOrNull(linePrefix(source, last).contentStart) == '=') 1 else 2
                }
            }
        }.distinct()
        val lists = lines.map { linePrefix(source, it).list?.style }.distinct()
        return MarkdownTools(levels.singleOrNull(), lists.singleOrNull(), lines.any { linePrefix(source, it).list?.style == ListStyle.TASK }, lines.any { linePrefix(source, it).list != null })
    }

    fun calculate(source: String, selection: EditSelection, command: MarkdownCommand): MarkdownEdit {
        require(selection.min >= 0 && selection.max <= source.length)
        val context = EditingContext(source)
        val block = command !is MarkdownCommand.Inline
        if (context.protected(selection, block)) return MarkdownEdit(emptyList(), selection, "所选位置属于属性、代码或不适合此操作的结构，请选择正文")
        if (command is MarkdownCommand.Template || command is MarkdownCommand.InsertLink) {
            var inCode = false
            context.walk { node -> if (node is Code) context.range(node)?.let { if (selection.min in it || selection.max - 1 in it) inCode = true } }
            if (inCode) return MarkdownEdit(emptyList(), selection, "请在行内代码之外插入内容")
        }
        return when (command) {
            is MarkdownCommand.Inline -> inlineEdit(context, selection, command.style)
            is MarkdownCommand.Template -> templateEdit(source, selection, command.template, command.parameter)
            is MarkdownCommand.InsertLink -> result(source, selection, listOf(SourceEdit(selection.min, selection.max, command.markup)), EditSelection(selection.min + command.markup.length))
            else -> blockEdit(context, selection, command)
        }
    }

    private fun blockEdit(context: EditingContext, selection: EditSelection, command: MarkdownCommand): MarkdownEdit {
        val source = context.source
        val lines = editLines(source, selection).filter { selection.collapsed || source.substring(it.start, it.end).isNotBlank() }
        val edits = mutableListOf<SourceEdit>()
        when (command) {
            is MarkdownCommand.AdjustHeading, is MarkdownCommand.Heading -> {
                val consumed = mutableSetOf<Int>()
                val setext = mutableMapOf<Int, Pair<Int, List<EditLine>>>()
                context.walk { node ->
                    if (node is Heading) context.range(node)?.let { range ->
                        val parts = editLines(source, EditSelection(range.first, range.last + 1))
                        val last = parts.last()
                        val underline = source.substring(linePrefix(source, last).contentStart, last.end).trim()
                        if (parts.size > 1 && underline.isNotEmpty() && (underline.all { it == '=' } || underline.all { it == '-' }))
                            parts.forEach { setext[it.start] = node.level to parts }
                    }
                }
                lines.map { headingLine(source, it) }.distinctBy { it.start }.forEach { line ->
                    if (line.start in consumed) return@forEach
                    setext[line.start]?.let { (oldLevel, parts) ->
                        val level = when (command) {
                            is MarkdownCommand.Heading -> command.level.coerceIn(0, 6)
                            is MarkdownCommand.AdjustHeading -> (oldLevel + command.delta).coerceIn(0, 6)
                        }
                        if (parts.size > 2 || oldLevel == level) {
                            val first = linePrefix(source, parts.first()).contentStart
                            val content = parts.dropLast(1).joinToString(" ") { source.substring(linePrefix(source, it).contentStart, it.end) }
                            edits += SourceEdit(first, parts.last().end, (if (level == 0) "" else "#".repeat(level) + " ") + content)
                            consumed += parts.map { it.start }
                            return@forEach
                        }
                    }
                    val old = heading(source, line)
                    val level = when (command) {
                        is MarkdownCommand.Heading -> command.level.coerceIn(0, 6)
                        is MarkdownCommand.AdjustHeading -> (old.level + command.delta).coerceIn(0, 6)
                    }
                    if (old.level == level) return@forEach
                    edits += SourceEdit(old.markerStart, old.markerEnd, if (level == 0) "" else "#".repeat(level) + " ")
                    old.closing?.let { edits += SourceEdit(it.first, it.last + 1, "") }
                    old.setext?.let { underline -> edits += SourceEdit(line.end, underline.end, ""); consumed += underline.start }
                }
            }
            is MarkdownCommand.List -> {
                val remove = lines.isNotEmpty() && lines.all { linePrefix(source, it).list?.style == command.style }
                val numbers = mutableMapOf<String, Int>()
                lines.forEach { line ->
                    val prefix = linePrefix(source, line); val list = prefix.list
                    val at = list?.start ?: prefix.contentStart
                    val end = list?.end ?: at
                    val outer = source.substring(line.start, at)
                    val number = numbers.getOrPut(outer) { list?.number ?: 1 }
                    val replacement = if (remove) "" else when (command.style) {
                        ListStyle.BULLET -> "- "
                        ListStyle.ORDERED -> "${number}. ".also { numbers[outer] = number + 1 }
                        ListStyle.TASK -> if (list?.checked == true) "- [x] " else "- [ ] "
                    }
                    edits += SourceEdit(at, end, replacement)
                }
            }
            is MarkdownCommand.Indent -> lines.forEach { line ->
                val prefix = linePrefix(source, line); val list = prefix.list ?: return@forEach
                if (command.delta > 0) {
                    if (edits.isEmpty()) {
                        val previous = if (line.start > 0) editLine(source, line.start - 1) else null
                        val parent = previous?.let { linePrefix(source, it).list }
                        if (parent == null || parent.start - previous.start < list.start - line.start)
                            return MarkdownEdit(emptyList(), selection, "请在当前项前保留同级列表项，作为缩进后的上级")
                    }
                    edits += SourceEdit(list.indentStart, list.indentStart, "    ")
                }
                else {
                    val whitespace = source.substring(list.indentStart, list.start)
                    if (whitespace.isNotEmpty()) {
                        val count = if (whitespace.startsWith('\t')) 1 else minOf(4, whitespace.length)
                        edits += SourceEdit(list.indentStart, list.indentStart + count, "")
                    } else if (prefix.containers.count { it.style != null } > 1) edits += SourceEdit(list.start, list.end, "")
                }
            }
            MarkdownCommand.ToggleTask -> {
                val tasks = lines.mapNotNull { linePrefix(source, it).list?.takeIf { it.style == ListStyle.TASK } }
                val check = !tasks.all { it.checked }
                tasks.forEach { edits += SourceEdit(it.checkbox, it.checkbox + 1, if (check) "x" else " ") }
            }
            MarkdownCommand.Quote -> {
                val remove = lines.isNotEmpty() && lines.all { linePrefix(source, it).quote != null }
                lines.forEach { line ->
                    val prefix = linePrefix(source, line); val quote = prefix.quote
                    if (remove && quote != null) edits += SourceEdit(quote.start, quote.end, "")
                    else if (quote == null) edits += SourceEdit(prefix.contentStart, prefix.contentStart, "> ")
                }
            }
            else -> Unit
        }
        return result(source, selection, edits)
    }
}

internal data class EditLine(val start: Int, val end: Int, val terminatorEnd: Int)
internal fun editLine(source: CharSequence, position: Int): EditLine {
    val start = source.lastIndexOf('\n', position - 1) + 1
    val next = source.indexOf('\n', start).let { if (it < 0) source.length else it }
    val end = if (next > start && source[next - 1] == '\r') next - 1 else next
    return EditLine(start, end, if (next < source.length) next + 1 else next)
}
internal fun editLines(source: String, selection: EditSelection): List<EditLine> {
    val result = mutableListOf<EditLine>(); var line = editLine(source, selection.min)
    do {
        result += line
        if (line.terminatorEnd >= source.length || line.terminatorEnd >= selection.max) break
        line = editLine(source, line.terminatorEnd)
    } while (true)
    return result
}
internal data class EditContainer(val start: Int, val end: Int, val indentStart: Int, val style: ListStyle? = null, val checked: Boolean = false, val checkbox: Int = -1, val number: Int? = null)
internal data class EditPrefix(val contentStart: Int, val containers: List<EditContainer>) {
    val list get() = containers.lastOrNull { it.style != null }
    val quote get() = containers.lastOrNull { it.style == null }
}
internal fun linePrefix(source: CharSequence, line: EditLine): EditPrefix {
    var at = line.start + if (line.start == 0 && source.getOrNull(0) == '\uFEFF') 1 else 0
    val containers = mutableListOf<EditContainer>()
    repeat(32) {
        val indent = at
        while (at < line.end && source[at] in " \t") at++
        if (at < line.end && source[at] == '>') {
            val start = at++; if (at < line.end && source[at] == ' ') at++
            containers += EditContainer(start, at, indent)
        } else {
            val start = at
            var end = at
            var number: Int? = null
            if (end < line.end && source[end] in "-+*") end++
            else {
                while (end < line.end && source[end] in '0'..'9' && end - start < 10) end++
                if (end - start !in 1..9 || end >= line.end || source[end] !in ".)") return EditPrefix(at, containers)
                number = source.subSequence(start, end).toString().toInt()
                end++
            }
            if (end == start || end < line.end && source[end] !in " \t") return EditPrefix(at, containers)
            at = end
            while (at < line.end && source[at] in " \t") at++
            val task = at + 2 < line.end && source[at] == '[' && source[at + 1] in " xX" && source[at + 2] == ']' && (at + 3 == line.end || source[at + 3] in " \t")
            val checkbox = if (task) at + 1 else -1
            val checked = task && source[checkbox] in "xX"
            if (task) { at += 3; while (at < line.end && source[at] in " \t") at++ }
            containers += EditContainer(start, at, indent, if (task) ListStyle.TASK else if (number != null) ListStyle.ORDERED else ListStyle.BULLET, checked, checkbox, number)
        }
    }
    return EditPrefix(at, containers)
}
internal data class EditHeading(val level: Int, val markerStart: Int, val markerEnd: Int, val closing: IntRange? = null, val setext: EditLine? = null)
internal fun headingLine(source: String, line: EditLine): EditLine {
    if (line.start == 0) return line
    val previous = editLine(source, line.start - 1)
    return if (heading(source, previous).setext?.start == line.start) previous else line
}
internal fun heading(source: String, line: EditLine): EditHeading {
    val at = linePrefix(source, line).contentStart
    var hashes = at
    while (hashes < line.end && source[hashes] == '#' && hashes - at < 7) hashes++
    if (hashes - at in 1..6 && (hashes == line.end || source[hashes] in " \t")) {
        var end = hashes
        while (end < line.end && source[end] in " \t") end++
        var tailEnd = line.end
        while (tailEnd > end && source[tailEnd - 1] in " \t") tailEnd--
        var tailStart = tailEnd
        while (tailStart > end && source[tailStart - 1] == '#') tailStart--
        val closing = if (tailStart < tailEnd && tailStart > end && source[tailStart - 1] in " \t") {
            while (tailStart > end && source[tailStart - 1] in " \t") tailStart--
            tailStart until line.end
        } else null
        return EditHeading(hashes - at, at, end, closing)
    }
    if (line.terminatorEnd < source.length && at < line.end) {
        val next = editLine(source, line.terminatorEnd)
        val prefix = linePrefix(source, next)
        // 普通长段落无需复制下一整行来判断 Setext。
        if (source.getOrNull(prefix.contentStart) in listOf('=', '-')) {
            var end = next.end
            while (end > prefix.contentStart && source[end - 1].isWhitespace()) end--
            if ((prefix.contentStart until end).all { source[it] == source[prefix.contentStart] })
                return EditHeading(if (source[prefix.contentStart] == '=') 1 else 2, at, at, setext = next)
        }
    }
    return EditHeading(0, at, at)
}
internal fun result(source: String, selection: EditSelection, patches: List<SourceEdit>, explicit: EditSelection? = null): MarkdownEdit {
    val edits = patches.filter { source.substring(it.start, it.end) != it.replacement }.sortedBy { it.start }
    fun mapped(at: Int): Int {
        var delta = 0
        edits.forEach { edit ->
            if (at < edit.start) return at + delta
            if (at < edit.end) return edit.start + delta + edit.replacement.length
            delta += edit.replacement.length - (edit.end - edit.start)
        }
        return at + delta
    }
    return MarkdownEdit(edits, explicit ?: EditSelection(mapped(selection.start), mapped(selection.end)))
}

/** 命令可以后台完整解析；输入热路径使用独立的轻量续写识别。 */
internal class EditingContext(val source: String) {
    val lines = mutableListOf(0).also { starts -> source.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 } }
    val yaml = DocumentAnalyzer.yaml(source)
    private val parseSource = source.mapIndexed { i, c -> if ((yaml?.contains(i) == true || c == '\uFEFF') && c !in "\r\n") ' ' else c }.joinToString("")
    val root: Node = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build().parse(parseSource)
    fun range(node: Node): IntRange? {
        val first = node.sourceSpans.firstOrNull() ?: return null; val last = node.sourceSpans.last()
        return (lines[first.lineIndex] + first.columnIndex) until (lines[last.lineIndex] + last.columnIndex + last.length)
    }
    fun walk(node: Node = root, visit: (Node) -> Unit) {
        visit(node); var child = node.firstChild
        while (child != null) { walk(child, visit); child = child.next }
    }
    fun protected(selection: EditSelection, block: Boolean): Boolean {
        fun touches(range: IntRange): Boolean = if (selection.collapsed) selection.min in range else selection.min <= range.last && selection.max > range.first
        if (yaml?.let(::touches) == true) return true
        var protected = false
        walk { node -> if (node is FencedCodeBlock || node is IndentedCodeBlock || node is HtmlBlock || block && node is TableBlock) range(node)?.let { if (touches(it)) protected = true } }
        return protected
    }
}
