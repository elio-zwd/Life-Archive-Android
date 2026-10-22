package com.elio.lifearchive.core.document

import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension

/** 仅供显示的投影，变换后的文本不进入保存接口。data-source 始终指向原始正文。 */
object HtmlProjection {
    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    fun render(source: String): String {
        val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
        val masked = DocumentAnalyzer.masked(source)
        data class Replacement(val start: Int, val end: Int, val text: String)
        val changes = mutableListOf<Replacement>()
        val footnotes = linkedMapOf<String, String>()
        DocumentAnalyzer.yaml(source)?.let { changes += Replacement(it.first, it.last + 1, source.substring(it).map { c -> if (c == '\n' || c == '\r') c else ' ' }.joinToString("")) }
        Regex("(?m)^\\[\\^([^]]+)]:[ \\t]*(.*)(?:\\r?\\n(?: {4}|\\t).*)*").findAll(masked).forEach { match ->
            footnotes[match.groupValues[1]] = source.substring(match.range).substringAfter(':').trim().replace(Regex("\\r?\\n(?: {4}|\\t)"), "\n")
            changes += Replacement(match.range.first, match.range.last + 1, "\n")
        }
        var inlineCount = 0
        Regex("(!?)\\[\\[([^]\\r\\n]+)]]").findAll(masked).forEach { match ->
            val target = match.groupValues[2].replace("\\|", "|"); val embed = match.groupValues[1].isNotEmpty()
            val html = if (embed) "<span class=\"embed\" data-target=\"${escape(target)}\"></span>" else "<a class=\"internal-link\" data-target=\"${escape(target.substringBefore('|'))}\" href=\"#\">${escape(target.substringAfter('|', target))}</a>"
            changes += Replacement(match.range.first, match.range.last + 1, html)
        }
        Regex("==(?=\\S)(.+?)(?<=\\S)==").findAll(masked).forEach { match ->
            changes += Replacement(match.range.first, match.range.first + 2, "<mark>")
            changes += Replacement(match.range.last - 1, match.range.last + 1, "</mark>")
        }
        Regex("\\[\\^([^]]+)](?!:)").findAll(masked).forEach { match ->
            changes += Replacement(match.range.first, match.range.last + 1, "<sup><a href=\"#fn-${escape(match.groupValues[1])}\" class=\"footnote-ref\">[${escape(match.groupValues[1])}]</a></sup>")
        }
        Regex("\\^\\[([^]\\n]+)]").findAll(masked).forEach { match ->
            val id = "inline-${inlineCount++}"; footnotes[id] = match.groupValues[1]
            changes += Replacement(match.range.first, match.range.last + 1, "<sup><a href=\"#fn-${escape(id)}\" class=\"footnote-ref\">[${escape(id.removePrefix("inline-"))}]</a></sup>")
        }
        Regex("(?s)\\$\\$(.+?)\\$\\$|(?<![\\\\$])\\$(?!\\$)([^$\\n]+?)(?<!\\\\)\\$(?!\\$)").findAll(masked).forEach { match ->
            val block = match.value.startsWith("$$"); val tex = if (block) source.substring(match.range).removeSurrounding("$$") else source.substring(match.range).removeSurrounding("$")
            changes += Replacement(match.range.first, match.range.last + 1, "<span class=\"math\" data-display=\"$block\" data-tex=\"${escape(tex)}\"></span>")
        }
        // 使用实际代码节点排除代码中的注释标记。
        val commentCandidates = source.replace("%%", "@@")
        val commentMask = DocumentAnalyzer.masked(commentCandidates)
        Regex("%%[\\s\\S]*?%%").findAll(source).filter { commentMask.getOrNull(it.range.first) == '@' }.forEach { changes += Replacement(it.range.first, it.range.last + 1, " ") }
        val text = StringBuilder(); val mapping = mutableListOf<Int>(); var cursor = 0
        changes.sortedBy { it.start }.forEach { change ->
            if (change.start < cursor) return@forEach
            for (i in cursor until change.start) { text.append(source[i]); mapping += i }
            change.text.forEach { text.append(it); mapping += change.start }
            cursor = change.end
        }
        for (i in cursor until source.length) { text.append(source[i]); mapping += i }; mapping += source.length
        val projected = text.toString().removePrefix("\uFEFF")
        val lines = mutableListOf(0); projected.forEachIndexed { i, c -> if (c == '\n') lines += i + 1 }
        val parser = Parser.builder().extensions(extensions).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
        val renderer = HtmlRenderer.builder().extensions(extensions).attributeProviderFactory {
            org.commonmark.renderer.html.AttributeProvider { node, _, attributes ->
                node.sourceSpans.firstOrNull()?.let { span -> attributes["data-source"] = mapping.getOrElse(lines[span.lineIndex] + span.columnIndex + if (text.startsWith("\uFEFF")) 1 else 0) { source.length }.toString() }
            }
        }.build()
        val html = renderer.render(parser.parse(projected))
        val properties = DocumentAnalyzer.yaml(source)?.let { "<details class=\"properties\" data-source=\"0\"><summary>属性</summary><pre>${escape(source.substring(it))}</pre></details>" }.orEmpty()
        val footer = if (footnotes.isEmpty()) "" else "<section class=\"footnotes\" data-source=\"${source.length}\"><hr><ol>" + footnotes.entries.joinToString("") { (id, body) -> "<li id=\"fn-${escape(id)}\">${render(body)}</li>" } + "</ol></section>"
        return properties + html + footer
    }
}
