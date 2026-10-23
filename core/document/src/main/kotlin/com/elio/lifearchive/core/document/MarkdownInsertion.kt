package com.elio.lifearchive.core.document

import java.net.URI

object MarkdownReferences {
    private fun label(value: String): String = value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\r\n", " ").replace('\n', ' ')

    fun external(text: String, target: String): String {
        val cleaned = target.trim()
        require(cleaned.isNotEmpty() && cleaned.none { it.code < 32 }) { "请输入有效的链接地址" }
        val encoded = cleaned.replace(" ", "%20").replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E")
        val uri = runCatching { URI(encoded) }.getOrNull()
        require(uri?.scheme?.lowercase() in setOf("http", "https", "mailto", "tel")) { "支持 HTTP(S)、邮件或电话链接" }
        if (uri!!.scheme.lowercase() in setOf("http", "https")) require(!uri.rawAuthority.isNullOrBlank()) { "链接缺少有效网站地址" }
        return "[${label(text.ifBlank { cleaned })}]($encoded)"
    }

    /** WikiLink 无法表示的文件名交由界面提供 Markdown 链接，不擅自改变目标。 */
    fun wiki(path: LibraryPath, text: String = "", embed: Boolean = false): String? {
        if (path.value.any { it in "[]#^|" } || text.any { it in "[]|\r\n" }) return null
        return (if (embed) "!" else "") + "[[${path.value}${if (text.isBlank()) "" else "|$text"}]]"
    }

    fun relative(from: LibraryPath, to: LibraryPath, text: String = "", embed: Boolean = false): String {
        val origin = from.value.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        val target = to.value.split('/')
        var common = 0
        while (common < minOf(origin.size, target.size) && origin[common] == target[common]) common++
        val path = List(origin.size - common) { ".." } + target.drop(common)
        val encoded = DocumentAnalyzer.encode(path.joinToString("/"))
        val display = text.ifBlank { to.value.substringAfterLast('/').removeSuffix(".md") }
        return (if (embed) "!" else "") + "[${label(display)}]($encoded)"
    }
}

internal fun templateEdit(source: String, selection: EditSelection, kind: InsertTemplate, parameter: String): MarkdownEdit {
    val nl = if (source.contains("\r\n")) "\r\n" else "\n"
    val chosen = source.substring(selection.min, selection.max)
    if (kind == InsertTemplate.FOOTNOTE) {
        var id = 1
        while (source.contains("[^fn$id]")) id++
        val reference = "[^fn$id]"
        val body = chosen.ifBlank { "脚注内容" }.replace("\n", "\n    ")
        val referenced = source.substring(0, selection.min) + reference + source.substring(selection.max)
        val separation = if (referenced.endsWith(nl + nl)) "" else if (referenced.endsWith('\n')) nl else nl + nl
        val prefix = "$separation$reference: "
        val definition = prefix + body + nl
        val at = source.length + reference.length - chosen.length + prefix.length
        return result(source, selection, listOf(SourceEdit(selection.min, selection.max, reference), SourceEdit(source.length, source.length, definition)), EditSelection(at, at + body.length))
    }
    var placeholder = chosen
    val text = when (kind) {
        InsertTemplate.TABLE -> {
            placeholder = "表头一"
            val cell = chosen.ifBlank { "内容" }.replace("\\", "\\\\").replace("|", "\\|").replace("\r\n", "\n").replace("\n", "<br>")
            "| 表头一 | 表头二 |${nl}| --- | --- |${nl}| $cell |  |${nl}|  |  |"
        }
        InsertTemplate.CODE_BLOCK -> {
            if (!Regex("[A-Za-z0-9_+#.\\-]{0,40}").matches(parameter)) return MarkdownEdit(emptyList(), selection, "语言名称只能包含字母、数字及 _ + # . -")
            placeholder = chosen.ifBlank { "在这里输入代码" }
            val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(placeholder).maxOfOrNull { it.value.length } ?: 0) + 1))
            "$fence${parameter.ifBlank { "text" }}$nl$placeholder$nl$fence"
        }
        InsertTemplate.DIVIDER -> { placeholder = chosen; "---" + if (chosen.isEmpty()) "" else nl + nl + chosen }
        InsertTemplate.CALLOUT -> {
            val type = parameter.ifBlank { "NOTE" }.uppercase()
            if (type !in setOf("NOTE", "TIP", "WARNING", "DANGER", "QUESTION", "EXAMPLE")) return MarkdownEdit(emptyList(), selection, "不支持的 Callout 类型")
            placeholder = chosen.ifBlank { "提示内容" }
            "> [!$type] 提示${nl}> " + placeholder.replace("\r\n", "\n").replace("\n", "$nl> ")
        }
        InsertTemplate.INLINE_MATH -> { placeholder = chosen.ifBlank { "x" }; "\$$placeholder\$" }
        InsertTemplate.BLOCK_MATH -> { placeholder = chosen.ifBlank { "x^2" }; "\$\$$nl$placeholder$nl\$\$" }
        InsertTemplate.MERMAID -> { placeholder = chosen.ifBlank { "flowchart TD${nl}    A[开始] --> B[结束]" }; "```mermaid$nl$placeholder${nl}```" }
        InsertTemplate.FOOTNOTE -> error("脚注由独立分支处理")
    }
    val inline = kind == InsertTemplate.INLINE_MATH
    val before = source.substring(0, selection.min)
    val after = source.substring(selection.max)
    val prefix = if (inline || before.isEmpty() || before.endsWith(nl + nl)) "" else if (before.endsWith('\n')) nl else nl + nl
    val suffix = if (inline) "" else if (after.isEmpty()) nl else if (after.startsWith(nl + nl)) "" else if (after.startsWith(nl)) nl else nl + nl
    val inserted = prefix + text + suffix
    val contentStart = if (kind == InsertTemplate.CALLOUT) text.indexOf(nl) + nl.length + 2
        else if (placeholder.isEmpty()) text.length else text.indexOf(placeholder).coerceAtLeast(0)
    val contentLength = if (kind == InsertTemplate.CALLOUT) text.length - contentStart else placeholder.length
    val at = selection.min + prefix.length + contentStart
    return result(source, selection, listOf(SourceEdit(selection.min, selection.max, inserted)), EditSelection(at, at + contentLength))
}
