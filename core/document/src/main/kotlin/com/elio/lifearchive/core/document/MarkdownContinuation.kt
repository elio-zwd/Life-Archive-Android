package com.elio.lifearchive.core.document

data class NewlineContinuation(val start: Int, val end: Int, val replacement: String, val cursor: Int)

object MarkdownContinuation {
    /** 仅用于一处折叠光标的换行输入。粘贴和组合输入由调用方提前排除。 */
    fun calculate(source: String, at: Int): NewlineContinuation? {
        require(at in 0..source.length)
        val line = editLine(source, at)
        val prefix = linePrefix(source, line)
        if (prefix.containers.isEmpty() || at < prefix.contentStart || protected(source, line)) return null
        val last = prefix.containers.last()
        val empty = source.substring(prefix.contentStart, line.end).isBlank()
        if (empty) {
            val indent = source.substring(last.indentStart, last.start)
            val start: Int; val replacement: String
            if (last.style != null && indent.isNotEmpty()) {
                val count = if (indent.startsWith('\t')) 1 else minOf(4, indent.length)
                start = last.indentStart; replacement = source.substring(start + count, at)
            } else { start = last.start; replacement = source.substring(last.end, at) }
            return NewlineContinuation(start, at, replacement, start + replacement.length)
        }
        val list = prefix.list
        val pieces = mutableListOf<SourceEdit>()
        prefix.containers.filter { it.style != null && it !== list }.forEach { pieces += SourceEdit(it.start, it.end, " ".repeat(it.end - it.start)) }
        if (list != null) {
            if (list.number != null) {
                val delimiter = source.indexOfAny(charArrayOf('.', ')'), list.start).coerceAtMost(list.end - 1)
                pieces += SourceEdit(list.start, delimiter, (list.number + 1).toString())
            }
            if (list.style == ListStyle.TASK) pieces += SourceEdit(list.checkbox, list.checkbox + 1, " ")
        }
        val rawPrefix = source.substring(line.start, prefix.contentStart)
        val local = pieces.map { it.copy(start = it.start - line.start, end = it.end - line.start) }
        val continued = MarkdownEdit(local, EditSelection(0)).apply(rawPrefix)
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val inserted = newline + continued
        return NewlineContinuation(at, at, inserted, at + inserted.length)
    }

    // 不构建整篇 AST。只扫描到当前行的围栏/属性/HTML/表格容器状态；普通按键不会运行此扫描。
    private fun protected(source: String, current: EditLine): Boolean {
        var at = 0; var fence: Char? = null; var fenceLength = 0; var yaml = false
        var html = false; var table = false; var previousPipe = false; var parentListIndent: Int? = null; var blanks = 0
        while (at <= current.start) {
            val line = editLine(source, at)
            val raw = source.substring(line.start, line.end).removePrefix("\uFEFF")
            val trim = raw.trim()
            if (at == 0 && trim == "---") yaml = true
            else if (yaml && trim in setOf("---", "...")) { yaml = false; if (line.start == current.start) return true }
            if (line.start == current.start && yaml) return true
            if (!yaml) {
                val content = if (raw.any { it == '`' || it == '~' || it == '<' || it == '>' }) source.substring(linePrefix(source, line).contentStart, line.end).trimStart() else trim
                val run = content.takeWhile { it == '`' || it == '~' }
                if (fence != null) {
                    if (line.start == current.start) return true
                    if (run.length >= fenceLength && run.all { it == fence } && content.drop(run.length).isBlank()) fence = null
                } else if (run.length >= 3 && run.all { it == run[0] }) {
                    if (line.start == current.start) return true
                    fence = run[0]; fenceLength = run.length
                }
                if (content.startsWith("<!--") || Regex("^</?(?:div|table|pre|script|style|section|article|details)(?:[ >]|$)", RegexOption.IGNORE_CASE).containsMatchIn(content)) html = true
                if (content.isBlank() || content.contains("-->") || Regex("</(?:div|table|pre|script|style|section|article|details)>", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
                    if (line.start == current.start && html) return true
                    html = false
                }
                val separator = content.contains('|') && content.split('|').filter { it.isNotBlank() }.all { it.trim().matches(Regex(":?-+:?")) }
                if (separator && previousPipe) table = true
                if (content.isBlank() || !content.contains('|')) table = false
                if (line.start == current.start) {
                    if (html || table) return true
                    val prefix = linePrefix(source, line)
                    val start = prefix.quote?.end ?: line.start
                    val indent = source.substring(start, prefix.list?.start ?: prefix.contentStart).takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }
                    return indent >= 4 && (parentListIndent == null || indent <= parentListIndent)
                }
                if (trim.isBlank()) { blanks++; if (blanks > 1) parentListIndent = null }
                else {
                    blanks = 0
                    val prefix = linePrefix(source, line)
                    val list = prefix.list
                    if (list != null) parentListIndent = list.start - (prefix.quote?.end ?: line.start)
                    else if (!raw.startsWith(' ') && !raw.startsWith('\t') && prefix.quote == null) parentListIndent = null
                }
                previousPipe = content.contains('|')
            }
            if (line.terminatorEnd <= at || line.terminatorEnd > current.start) break
            at = line.terminatorEnd
        }
        return false
    }
}
