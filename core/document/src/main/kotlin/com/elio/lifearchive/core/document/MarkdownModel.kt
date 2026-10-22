package com.elio.lifearchive.core.document

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

enum class BlockKind { PARAGRAPH, HEADING, QUOTE, CALLOUT, CODE, TABLE, IMAGE, RAW, PROPERTIES, DIVIDER }
enum class TextMark { NORMAL, BOLD, ITALIC, STRIKE, CODE, LINK, HEADING, QUOTE }
data class InlineRun(val text: String, val marks: Set<TextMark> = emptySet(), val destination: String? = null)
data class ReaderBlock(
    val kind: BlockKind, val start: Int, val end: Int,
    val runs: List<InlineRun> = emptyList(), val level: Int = 0,
    val raw: String = "", val label: String = "", val destination: String = "",
    val rows: List<List<String>> = emptyList(),
)
data class MarkRange(val start: Int, val end: Int, val mark: TextMark)
data class MarkerRange(val start: Int, val end: Int)
data class ParsedDocument(val source: String, val blocks: List<ReaderBlock>, val styles: List<MarkRange>, val markers: List<MarkerRange>)

/** 解析仅生成展示投影。原文始终由 EditorSession 持有，不从 AST 序列化回写。 */
class MarkdownParser {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

    fun parse(source: String): ParsedDocument {
        val lines = mutableListOf(0)
        source.forEachIndexed { index, c -> if (c == '\n') lines += index + 1 }
        fun range(node: Node): Pair<Int, Int> {
            val spans = node.sourceSpans
            if (spans.isEmpty()) return 0 to 0
            val first = spans.first(); val last = spans.last()
            return (lines[first.lineIndex] + first.columnIndex) to (lines[last.lineIndex] + last.columnIndex + last.length)
        }
        val blocks = mutableListOf<ReaderBlock>()
        val styles = mutableListOf<MarkRange>()
        val markers = mutableListOf<MarkerRange>()
        val yaml = DocumentAnalyzer.yaml(source)
        val parseSource = buildString {
            source.forEachIndexed { i, c -> append(if ((yaml != null && i <= yaml.last && c != '\n' && c != '\r') || c == '\uFEFF') ' ' else c) }
        }
        yaml?.let { blocks += ReaderBlock(BlockKind.PROPERTIES, 0, it.last + 1, raw = source.substring(it), label = "属性 · 原文保留") }
        val document = parser.parse(parseSource)
        fun runs(node: Node, marks: Set<TextMark> = emptySet(), destination: String? = null): List<InlineRun> {
            val result = mutableListOf<InlineRun>()
            var child = node.firstChild
            while (child != null) {
                val n = child
                when (n) {
                    is Text -> result += InlineRun(n.literal, marks, destination)
                    is SoftLineBreak -> result += InlineRun("\n", marks, destination)
                    is HardLineBreak -> result += InlineRun("\n", marks, destination)
                    is Code -> result += InlineRun(n.literal, marks + TextMark.CODE, destination)
                    is StrongEmphasis -> result += runs(n, marks + TextMark.BOLD, destination)
                    is Emphasis -> result += runs(n, marks + TextMark.ITALIC, destination)
                    is Strikethrough -> result += runs(n, marks + TextMark.STRIKE, destination)
                    is Link -> result += runs(n, marks + TextMark.LINK, n.destination)
                    is Image -> result += InlineRun("[图片：${runs(n).joinToString("") { it.text }}]", marks)
                    is HtmlInline -> result += InlineRun(n.literal, marks + TextMark.CODE)
                    else -> result += runs(n, marks, destination)
                }
                child = n.next
            }
            return result
        }
        fun highlight(node: Node) {
            val (start, end) = range(node)
            if (end > start) {
                val original = source.substring(start, end)
                val mark = when (node) {
                    is Heading -> TextMark.HEADING
                    is StrongEmphasis -> TextMark.BOLD
                    is Emphasis -> TextMark.ITALIC
                    is Strikethrough -> TextMark.STRIKE
                    is Code, is FencedCodeBlock, is IndentedCodeBlock -> TextMark.CODE
                    is BlockQuote -> TextMark.QUOTE
                    is Link -> TextMark.LINK
                    else -> null
                }
                mark?.let { styles += MarkRange(start, end, it) }
                val delimiter = when (node) {
                    is StrongEmphasis -> 2
                    is Emphasis -> 1
                    is Strikethrough -> 2
                    is Code -> original.takeWhile { it == '`' }.length
                    else -> 0
                }
                if (delimiter > 0 && end - start > delimiter * 2) {
                    markers += MarkerRange(start, start + delimiter)
                    markers += MarkerRange(end - delimiter, end)
                }
                if (node is Heading) {
                    Regex("^#{1,6} +").find(original)?.let { markers += MarkerRange(start, start + it.value.length) }
                }
                if (node is BlockQuote) {
                    Regex("(?m)^ *> ?").findAll(original).forEach { markers += MarkerRange(start + it.range.first, start + it.range.last + 1) }
                }
            }
            // 代码块中不再应用 Markdown 标记隐藏。
            if (node !is FencedCodeBlock && node !is IndentedCodeBlock) {
                var child = node.firstChild
                while (child != null) { highlight(child); child = child.next }
            }
        }
        highlight(document)
        fun flatten(node: Node, prefix: String = "", depth: Int = 0) {
            val (start, end) = range(node)
            val raw = source.substring(start, end)
            when (node) {
                is Heading -> blocks += ReaderBlock(BlockKind.HEADING, start, end, runs(node), level = node.level)
                is Paragraph -> {
                    val only = node.firstChild
                    when {
                        raw.contains("![[") || raw.contains("[[") || raw.contains("$$") -> blocks += ReaderBlock(BlockKind.RAW, start, end, raw = raw, label = "复杂引用 / 公式 · 本轮保留源码")
                        only is Image && only.next == null -> blocks += ReaderBlock(BlockKind.IMAGE, start, end, raw = raw, label = runs(only).joinToString("") { it.text }, destination = only.destination)
                        else -> {
                            val content = runs(node).toMutableList()
                            val first = content.firstOrNull()
                            val task = first?.text?.let { Regex("^\\[([ xX])] ").find(it) }
                            if (prefix.isNotEmpty() && task != null) {
                                content[0] = first.copy(text = (if (task.groupValues[1] == " ") "☐  " else "☑  ") + first.text.drop(task.value.length))
                            }
                            blocks += ReaderBlock(BlockKind.PARAGRAPH, start, end, (if (task != null) emptyList() else listOf(InlineRun(prefix))) + content, level = depth)
                        }
                    }
                }
                is BlockQuote -> {
                    val content = raw.lineSequence().joinToString("\n") { it.trimStart().removePrefix(">").removePrefix(" ") }
                    val callout = Regex("^\\[!([^]]+)]([+-])?([^\\n]*)").find(content)
                    blocks += ReaderBlock(if (callout == null) BlockKind.QUOTE else BlockKind.CALLOUT, start, end,
                        if (callout == null) runs(node) else listOf(InlineRun(content.substringAfter('\n', ""))),
                        label = callout?.let { it.groupValues[3].trim().ifEmpty { it.groupValues[1] } }.orEmpty())
                }
                is FencedCodeBlock -> blocks += ReaderBlock(if (node.info == "mermaid") BlockKind.RAW else BlockKind.CODE, start, end,
                    raw = node.literal, label = if (node.info == "mermaid") "Mermaid · 本轮保留源码" else node.info.ifBlank { "代码" })
                is IndentedCodeBlock -> blocks += ReaderBlock(BlockKind.CODE, start, end, raw = node.literal, label = "代码")
                is HtmlBlock -> blocks += ReaderBlock(BlockKind.RAW, start, end, raw = raw, label = "HTML · 本轮保留源码")
                is ThematicBreak -> blocks += ReaderBlock(BlockKind.DIVIDER, start, end)
                is TableBlock -> {
                    val rows = mutableListOf<List<String>>()
                    fun visit(n: Node) {
                        if (n is TableRow) {
                            val cells = mutableListOf<String>(); var c = n.firstChild
                            while (c != null) { if (c is TableCell) cells += runs(c).joinToString("") { it.text }; c = c.next }
                            rows += cells
                        } else { var c = n.firstChild; while (c != null) { visit(c); c = c.next } }
                    }
                    visit(node)
                    blocks += ReaderBlock(BlockKind.TABLE, start, end, rows = rows)
                }
                is BulletList, is OrderedList -> {
                    var item = node.firstChild; var number = (node as? OrderedList)?.markerStartNumber ?: 1
                    while (item != null) {
                        var child = item.firstChild; var first = true
                        while (child != null) {
                            flatten(child, if (first) if (node is OrderedList) "${number}.  " else "•  " else "", depth + 1)
                            first = false; child = child.next
                        }
                        number++; item = item.next
                    }
                }
                else -> { var child = node.firstChild; while (child != null) { flatten(child); child = child.next } }
            }
        }
        flatten(document)
        return ParsedDocument(source, blocks, styles, markers.distinct().sortedBy { it.start })
    }
}
