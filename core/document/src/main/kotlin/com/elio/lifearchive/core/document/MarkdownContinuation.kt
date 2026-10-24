package com.elio.lifearchive.core.document

data class NewlineContinuation(val start: Int, val end: Int, val replacement: String, val cursor: Int) {
    fun isValid(sourceLength: Int, at: Int): Boolean = start in 0..at && end == at && end <= sourceLength &&
        cursor in start..(sourceLength.toLong() - (end - start) + replacement.length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** 不含正文副本的行状态检查点；调用方保证 unchangedBefore 之前仍是建索引时的原文。 */
class ContinuationContext private constructor(private val starts: IntArray, private val states: List<ContinuationState>) {
    internal fun checkpoint(before: Int): Pair<Int, ContinuationState> {
        var low = 0; var high = starts.size
        while (low < high) { val mid = (low + high) ushr 1; if (starts[mid] <= before) low = mid + 1 else high = mid }
        val index = (low - 1).coerceAtLeast(0)
        return starts[index] to states[index]
    }

    companion object {
        fun build(source: CharSequence): ContinuationContext {
            val starts = mutableListOf<Int>(); val states = mutableListOf<ContinuationState>()
            var at = 0; var state = ContinuationState()
            while (true) {
                starts += at; states += state
                val line = editLine(source, at)
                state = scanContinuationLine(source, line, state).first
                if (line.terminatorEnd <= at || line.terminatorEnd == source.length && line.end == source.length) break
                at = line.terminatorEnd
            }
            return ContinuationContext(starts.toIntArray(), states)
        }
    }
}

internal data class ContinuationState(
    val fence: Char? = null, val fenceLength: Int = 0, val yaml: Boolean = false,
    val html: Boolean = false, val table: Boolean = false, val previousPipe: Boolean = false,
    val parentListIndent: Int? = null, val blanks: Int = 0,
)

private val htmlOpen = Regex("^</?(?:div|table|pre|script|style|section|article|details)(?:[ >]|$)", RegexOption.IGNORE_CASE)
private val htmlClose = Regex("</(?:div|table|pre|script|style|section|article|details)>", RegexOption.IGNORE_CASE)
private val tableCell = Regex(":?-+:?")

private fun containerIndent(source: CharSequence, line: EditLine, prefix: EditPrefix): Int {
    val container = prefix.list
    // 缩进属于该容器前面的空白，后面的引用不能作为区间起点。
    val start = container?.indentStart ?: prefix.containers.lastOrNull()?.end ?: line.start
    val end = container?.start ?: prefix.contentStart
    var width = 0
    for (i in start until end) { if (source[i] !in " \t") break; width += if (source[i] == '\t') 4 else 1 }
    return width
}

private fun scanContinuationLine(source: CharSequence, line: EditLine, before: ContinuationState): Pair<ContinuationState, Boolean> {
    val prefix = linePrefix(source, line)
    val raw = source.subSequence(line.start, line.end).toString().removePrefix("\uFEFF")
    val content = source.subSequence(prefix.contentStart, line.end).toString().trimStart()
    val trim = raw.trim()
    if (before.yaml) return before.copy(yaml = trim !in setOf("---", "...")) to true
    if (line.start == 0 && trim == "---") return before.copy(yaml = true) to true
    val run = content.takeWhile { it == '`' || it == '~' }
    if (before.fence != null) {
        val closed = run.length >= before.fenceLength && run.all { it == before.fence } && content.drop(run.length).isBlank()
        return (if (closed) before.copy(fence = null, fenceLength = 0, previousPipe = false, table = false) else before) to true
    }
    if (run.length >= 3 && run.all { it == run[0] }) return before.copy(fence = run[0], fenceLength = run.length, previousPipe = false, table = false) to true
    var html = before.html || content.startsWith("<!--") || htmlOpen.containsMatchIn(content)
    val protectedHtml = html
    if (content.isBlank() || content.contains("-->") || htmlClose.containsMatchIn(content)) html = false
    val pipe = content.contains('|')
    val separator = pipe && content.split('|').filter { it.isNotBlank() }.all { tableCell.matches(it.trim()) }
    val table = pipe && content.isNotBlank() && (before.table || separator && before.previousPipe)
    val indent = containerIndent(source, line, prefix)
    val indentedCode = indent >= 4 && (before.parentListIndent == null || indent <= before.parentListIndent)
    val blanks = if (trim.isBlank()) before.blanks + 1 else 0
    val parent = when {
        blanks > 1 -> null
        prefix.list != null -> indent
        trim.isNotBlank() && !raw.startsWith(' ') && !raw.startsWith('\t') && prefix.quote == null -> null
        else -> before.parentListIndent
    }
    return before.copy(html = html, table = table, previousPipe = pipe, parentListIndent = parent, blanks = blanks) to (protectedHtml || table || indentedCode)
}

object MarkdownContinuation {
    /** 兼容独立命令调用；输入事务使用带检查点和扫描上限的入口。 */
    fun calculate(source: String, at: Int): NewlineContinuation? =
        calculate(source, at, null, 0, if (source.contains("\r\n")) "\r\n" else "\n", Int.MAX_VALUE)

    /** 不复制全文。索引未追上时只补扫有限范围，无法确认就保留输入法原来的换行。 */
    fun calculate(source: CharSequence, at: Int, context: ContinuationContext?, unchangedBefore: Int, newline: String, scanLimit: Int = 8192): NewlineContinuation? {
        if (at !in 0..source.length) return null
        val line = editLine(source, at)
        val prefix = linePrefix(source, line)
        if (prefix.containers.isEmpty() || prefix.containers.size >= 32 || at < prefix.contentStart || at > line.end) return null
        val checkpoint = context?.checkpoint(minOf(line.start, unchangedBefore.coerceAtLeast(0))) ?: (0 to ContinuationState())
        if (line.end.toLong() - checkpoint.first > scanLimit) return null
        var offset = checkpoint.first; var state = checkpoint.second
        while (offset <= line.start) {
            val current = editLine(source, offset)
            val (next, protected) = scanContinuationLine(source, current, state)
            if (current.start == line.start) { if (protected) return null; break }
            if (current.terminatorEnd <= offset) return null
            offset = current.terminatorEnd; state = next
        }
        val last = prefix.containers.last()
        val empty = (prefix.contentStart until line.end).all { source[it].isWhitespace() }
        if (empty) {
            val indent = source.subSequence(last.indentStart, last.start).toString()
            val start: Int; val replacement: String
            if (last.style != null && indent.isNotEmpty()) {
                val count = if (indent.startsWith('\t')) 1 else minOf(4, indent.length)
                start = last.indentStart; replacement = source.subSequence(start + count, at).toString()
            } else { start = last.start; replacement = source.subSequence(last.end, at).toString() }
            return NewlineContinuation(start, at, replacement, start + replacement.length)
        }
        val list = prefix.list
        if (list?.number == 999_999_999) return null
        val pieces = mutableListOf<SourceEdit>()
        prefix.containers.filter { it.style != null && it !== list }.forEach { pieces += SourceEdit(it.start, it.end, " ".repeat(it.end - it.start)) }
        if (list != null) {
            if (list.number != null) {
                var delimiter = list.start
                while (delimiter < list.end && source[delimiter] in '0'..'9') delimiter++
                pieces += SourceEdit(list.start, delimiter, (list.number + 1).toString())
            }
            if (list.style == ListStyle.TASK) pieces += SourceEdit(list.checkbox, list.checkbox + 1, " ")
        }
        val rawPrefix = source.subSequence(line.start, prefix.contentStart).toString()
        val local = pieces.map { it.copy(start = it.start - line.start, end = it.end - line.start) }
        val continued = MarkdownEdit(local, EditSelection(0)).apply(rawPrefix)
        val inserted = newline + continued
        return NewlineContinuation(at, at, inserted, at + inserted.length)
    }
}
