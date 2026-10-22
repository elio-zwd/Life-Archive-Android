package com.elio.lifearchive.core.document

import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.*
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.IncludeSourceSpans

enum class LinkSyntax { WIKI, MARKDOWN, HTML }
data class SourceLink(val start: Int, val end: Int, val target: String, val wiki: Boolean, val embed: Boolean, val mermaidNode: String? = null, val implicitNode: Boolean = false, val syntax: LinkSyntax = if (wiki) LinkSyntax.WIKI else LinkSyntax.MARKDOWN)
data class SourceAnchor(val name: String, val start: Int, val end: Int, val level: Int = 0)
data class DocumentAnalysis(val tags: List<String>, val aliases: List<String>, val title: String?, val headings: List<SourceAnchor>, val blocks: List<SourceAnchor>, val links: List<SourceLink>, val propertyTags: List<String> = emptyList())

/** 掩码与原文等长，链接改写只替换已识别目标的源区间。 */
object DocumentAnalyzer {
    private val codeParser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    private fun tree(source: String): Node = codeParser.parse(if (source.startsWith('\uFEFF')) " " + source.drop(1) else source)
    private fun withoutComment(value: String): String {
        var quote = ' '; var escaped = false
        value.forEachIndexed { i, c ->
            if (quote == ' ' && c == '#' && (i == 0 || value[i - 1].isWhitespace())) return value.substring(0, i).trimEnd()
            if (!escaped && c in "\"'") { if (quote == ' ') quote = c else if (quote == c) quote = ' ' }
            escaped = !escaped && c == '\\' && quote == '"'
        }
        return value
    }
    fun yaml(source: String): IntRange? = Regex("\\A\\uFEFF?---\\r?\\n(?:[\\s\\S]*?\\r?\\n)?(?:---|\\.\\.\\.)(?:\\r?\\n|$)").find(source)?.range
    fun unquote(value: String): String = value.trim().let { text ->
        when {
            text.startsWith('"') && text.endsWith('"') -> runCatching { kotlinx.serialization.json.Json.decodeFromString<String>(text) }.getOrDefault(text.drop(1).dropLast(1))
            text.startsWith('\'') && text.endsWith('\'') -> text.drop(1).dropLast(1).replace("''", "'")
            else -> text.substringBefore(" #").trimEnd()
        }
    }
    private fun property(source: String, name: String): List<String> {
        val range = yaml(source) ?: return emptyList()
        val yaml = source.substring(range)
        val body = yaml.substringAfter('\n').replace(Regex("\\r?\\n(?:---|\\.\\.\\.)(?:\\r?\\n)?$"), "").trim()
        if (body.startsWith('{')) {
            val value = runCatching { Json.parseToJsonElement(body).jsonObject[name] }.getOrNull() ?: return emptyList()
            return when (value) { is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }; is JsonPrimitive -> listOfNotNull(value.contentOrNull); else -> emptyList() }
        }
        val match = Regex("(?m)^${Regex.escape(name)}:[ \\t]*(.*)$").find(yaml) ?: return emptyList()
        val value = withoutComment(match.groupValues[1].trim().trimEnd('\r')).replaceFirst(Regex("^&[A-Za-z0-9_-]+(?:\\s+|$)"), "").trim()
        if (value.startsWith('[') && value.endsWith(']')) return Regex("\"(?:\\\\.|[^\"])*\"|'(?:''|[^'])*'|[^,]+").findAll(value.drop(1).dropLast(1)).map { unquote(it.value) }.filter { it.isNotBlank() }.toList()
        if (value.isNotBlank() && !value.startsWith('#')) return listOf(unquote(value))
        return yaml.substring(match.range.last + 1).lineSequence().drop(1).takeWhile { it.isBlank() || it.startsWith(' ') || it.startsWith('\t') || it.startsWith('-') }.mapNotNull { Regex("^\\s*-\\s+(.+)$").find(it)?.groupValues?.get(1)?.let(::unquote) }.toList()
    }
    fun masked(source: String, includeMath: Boolean = false): String {
        val chars = source.toCharArray()
        fun hide(range: IntRange) { range.forEach { i -> if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ' } }
        // 同长度掩码排除代码、注释；不能在普通文字中作全局名称替换。
        val starts = mutableListOf(0); source.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        fun code(node: Node) { node.sourceSpans.forEach { span -> val start = starts[span.lineIndex] + span.columnIndex; hide(start until start + span.length) } }
        tree(source).accept(object : AbstractVisitor() {
            override fun visit(node: FencedCodeBlock) = code(node)
            override fun visit(node: IndentedCodeBlock) = code(node)
            override fun visit(node: Code) = code(node)
        })
        Regex("%%[\\s\\S]*?%%|<!--[\\s\\S]*?-->").findAll(String(chars)).forEach { hide(it.range) }
        if (includeMath) Regex("(?s)\\$\\$(.+?)\\$\\$|(?<![\\\\$])\\$(?!\\$)([^$\\n]+?)(?<!\\\\)\\$(?!\\$)").findAll(String(chars)).forEach { hide(it.range) }
        return String(chars)
    }
    fun analyze(source: String): DocumentAnalysis {
        val text = masked(source, includeMath = true)
        val links = mutableListOf<SourceLink>()
        Regex("(!?)\\[\\[([^]\\r\\n]+)]]").findAll(text).forEach { match ->
            val target = match.groupValues[2].substringBefore('|').removeSuffix("\\")
            val start = match.range.first + match.groupValues[1].length + 2
            links += SourceLink(start, start + target.length, target, true, match.groupValues[1].isNotEmpty())
        }
        val headings = mutableListOf<SourceAnchor>()
        val lineStarts = mutableListOf(0); source.forEachIndexed { i, c -> if (c == '\n') lineStarts += i + 1 }
        fun offset(node: Node): Pair<Int, Int>? {
            val first = node.sourceSpans.firstOrNull() ?: return null; val last = node.sourceSpans.last()
            return (lineStarts[first.lineIndex] + first.columnIndex) to (lineStarts[last.lineIndex] + last.columnIndex + last.length)
        }
        tree(source).accept(object : AbstractVisitor() {
            private fun markdown(node: Node, destination: String, image: Boolean, definition: Boolean = false) {
                val range = offset(node) ?: return
                val raw = source.substring(range.first, range.second)
                val at = if (definition) Regex("^ {0,3}\\[[^]]+]:[ \\t]*").find(raw)?.value?.length
                    else inlineDestinationStart(raw)
                if (at == null) return // 引用用法的目标由定义节点登记，不能把显示文字当作目标。
                val span = destinationRange(raw, at) ?: return
                val start = range.first + span.first; val end = range.first + span.last + 1
                if (text.substring(start, end).isNotBlank()) links += SourceLink(start, end, destination, false, image)
            }
            override fun visit(node: Link) { markdown(node, node.destination, false); visitChildren(node) }
            override fun visit(node: Image) { markdown(node, node.destination, true); visitChildren(node) }
            override fun visit(node: LinkReferenceDefinition) { if (!node.label.startsWith('^')) markdown(node, node.destination, false, true) }
            private fun html(node: Node) {
                val range = offset(node) ?: return
                val raw = text.substring(range.first, range.second)
                Regex("(?i)\\b(src|href)\\s*=\\s*([\"'])(.*?)\\2").findAll(raw).forEach { match ->
                    val group = match.groups[3]!!
                    links += SourceLink(range.first + group.range.first, range.first + group.range.last + 1, htmlTarget(group.value), false, match.groupValues[1].equals("src", true), syntax = LinkSyntax.HTML)
                }
            }
            override fun visit(node: HtmlInline) = html(node)
            override fun visit(node: HtmlBlock) = html(node)
            override fun visit(node: Heading) {
                val range = offset(node) ?: return
                if (yaml(source)?.contains(range.first) == true) return
                val label = StringBuilder()
                node.accept(object : AbstractVisitor() {
                    override fun visit(text: Text) { label.append(text.literal) }
                    override fun visit(code: Code) { label.append(code.literal) }
                    override fun visit(breakNode: SoftLineBreak) { label.append(' ') }
                })
                val name = Regex("\\[\\[([^]]+)]]").replace(label.toString()) { it.groupValues[1].substringAfter('|', it.groupValues[1]) }
                headings += SourceAnchor(name.trim(), range.first, range.second, node.level)
            }
            override fun visit(node: FencedCodeBlock) {
                if (node.info.trim() != "mermaid") return
                val range = offset(node) ?: return; val block = source.substring(range.first, range.second)
                val classes = Regex("(?m)\\bclass[ \\t]+([^;\\r\\n]+?)[ \\t]+internal-link[ \\t]*;?").findAll(block).toList()
                val ids = classes.flatMap { it.groupValues[1].split(',').map(String::trim) }.toSet()
                val labelled = mutableSetOf<String>()
                Regex("([\\p{L}_][\\p{L}\\p{N}_-]*)\\[[ \\t]*(\"[^\"\\n]*\"|[^]\\n]+?)[ \\t]*]").findAll(block).forEach { match ->
                    val id = match.groupValues[1]; if (id !in ids) return@forEach
                    val label = match.groups[2]!!; val value = unquote(label.value)
                    if ('<' !in value && '`' !in value) { links += SourceLink(range.first + label.range.first, range.first + label.range.last + 1, value, true, false, id); labelled += id }
                }
                (ids - labelled).filter { it.matches(Regex("[\\p{L}_][\\p{L}\\p{N}_-]*")) }.forEach { id ->
                    val match = Regex("(?<![\\p{L}\\p{N}_-])${Regex.escape(id)}(?![\\p{L}\\p{N}_-])").findAll(block).firstOrNull { found -> classes.none { found.range.first in it.range } }
                    if (match != null) links += SourceLink(range.first + match.range.first, range.first + match.range.last + 1, id, true, false, id, true)
                }
            }
        })
        val blocks = Regex("(?m)(?:^|[ \\t])\\^([A-Za-z0-9-]+)[ \\t]*\\r?$").findAll(text).map { m ->
            val lineStart = source.lastIndexOf('\n', (m.range.first - 1).coerceAtLeast(0)) + 1
            val standalone = source.substring(lineStart, m.range.first).isBlank()
            val prefix = source.substring(0, if (standalone) lineStart else m.range.first).trimEnd('\r', '\n', ' ', '\t')
            val start = Regex("\\r?\\n[ \\t]*\\r?\\n").findAll(prefix).lastOrNull()?.range?.last?.plus(1) ?: 0
            SourceAnchor(m.groupValues[1], start, m.range.last + 1)
        }.toList()
        val body = yaml(source)?.let { text.removeRange(it) } ?: text
        val inlineTags = Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]+)").findAll(body).map { it.groupValues[1] }.filter { it.any(Char::isLetter) }
        val propertyTags = property(source, "tags").map { it.removePrefix("#") }
        return DocumentAnalysis((propertyTags + inlineTags).distinct(), property(source, "aliases"), property(source, "title").firstOrNull(), headings, blocks, links.sortedBy { it.start }, propertyTags)
    }
    fun heading(analysis: DocumentAnalysis, fragment: String): SourceAnchor? {
        var parent: SourceAnchor? = null; var end = Int.MAX_VALUE
        fun comparable(value: String) = value.lowercase().replace(Regex("\\s+"), "-")
        for (name in fragment.split('#').filter { it.isNotEmpty() }) {
            val found = analysis.headings.firstOrNull { it.start > (parent?.start ?: -1) && it.start < end && (it.name.equals(name, true) || comparable(it.name) == comparable(name)) } ?: return null
            parent = found; end = analysis.headings.firstOrNull { it.start > found.start && it.level <= found.level }?.start ?: Int.MAX_VALUE
        }
        return parent
    }
    fun withTags(source: String, tags: List<String>): String {
        require(tags.all { it.isNotBlank() && it.none { c -> c.isWhitespace() || c in "#[],:" } }) { "标签不能包含空白或 YAML 分隔符" }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val replacement = "tags: [" + tags.distinct().joinToString(", ") { kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), it) } + "]"
        val range = yaml(source)
        if (range == null) { val bom = if (source.startsWith('\uFEFF')) "\uFEFF" else ""; return bom + "---$newline$replacement$newline---$newline" + source.removePrefix(bom) }
        val yaml = source.substring(range)
        val bodyStart = source.indexOf('\n') + 1
        val bodyEnd = range.first + Regex("(?m)^(---|\\.\\.\\.)[ \\t]*\\r?$").findAll(yaml).last().range.first
        val body = source.substring(bodyStart, bodyEnd)
        if (body.trimStart().startsWith('{')) {
            val obj = Json.parseToJsonElement(body).jsonObject
            val value = replacement.substringAfter("tags: ")
            // 仅替换 JSON 顶层 tags 的值，未知成员及其空白/顺序保持逐字不变。
            val member = jsonMember(body, "tags")
            val changed = if (member != null) body.replaceRange(member.first, member.last + 1, value) else {
                val at = body.indexOf('{') + 1
                body.substring(0, at) + "\"tags\":$value" + (if (obj.isEmpty()) "" else ",") + body.substring(at)
            }
            return source.substring(0, bodyStart) + changed + source.substring(bodyEnd)
        }
        val candidates = Regex("(?m)^(?:tags[ \\t]*|\"tags\"|'tags')[ \\t]*:").findAll(body).toList()
        require(candidates.size <= 1 && candidates.all { it.value == "tags:" }) { "该 tags 结构不能安全定位，请在源码中编辑" }
        val field = Regex("(?m)^tags:[^\\r\\n]*(?:\\r?\\n(?:[ \\t]+[^\\r\\n]*|-[ \\t]+[^\\r\\n]*))*").find(yaml)
        if (field != null) {
            val first = field.value.substringBefore('\n').trimEnd('\r').substringAfter(':')
            val value = withoutComment(first)
            val anchor = Regex("^\\s*(&[A-Za-z0-9_-]+)(?:\\s+|$)").find(value)
            val rest = if (anchor == null) field.value.substringAfter(':') else field.value.substringAfter(':').replaceFirst(anchor.value, "")
            require(!Regex("(?:^|[\\s\\[,])&[A-Za-z0-9_-]+|(?:^|\\s)!|^\\s*[|>{?]").containsMatchIn(rest)) { "该 tags 结构不能安全修改，请在源码中编辑" }
            val firstValue = withoutComment(rest.substringBefore('\n')).trim()
            val continuation = rest.substringAfter('\n', "").lineSequence().filter { it.isNotBlank() }.toList()
            require(continuation.isEmpty() || firstValue.isEmpty() && continuation.all { Regex("^[ \\t]*-[ \\t]+[^#].*$").matches(it) && !Regex(":(?:\\s|$)").containsMatchIn(withoutComment(it)) }) { "该 tags 结构不能安全修改，请在源码中编辑" }
            require(!Regex(":(?:\\s|$)").containsMatchIn(firstValue) && (!firstValue.startsWith('[') || firstValue.endsWith(']'))) { "该 tags 结构不能安全修改，请在源码中编辑" }
            val prefix = first.takeWhile { it == ' ' || it == '\t' }.ifEmpty { " " }
            val suffix = first.substring(value.length)
            val changed = "tags:" + prefix + (anchor?.groupValues?.get(1)?.plus(" ") ?: "") + replacement.substringAfter("tags: ") + suffix
            return source.replaceRange(range.first + field.range.first, range.first + field.range.last + 1, changed)
        }
        val insert = source.indexOf('\n') + 1
        return source.substring(0, insert) + replacement + newline + source.substring(insert)
    }
    private fun jsonMember(source: String, name: String): IntRange? {
        var depth = 0; var i = 0
        while (i < source.length) {
            when (source[i]) {
                '{', '[' -> { depth++; i++ }
                '}', ']' -> { depth--; i++ }
                '"' -> {
                    val start = i++; while (i < source.length) { if (source[i] == '\\') i += 2 else if (source[i++] == '"') break }
                    val key = runCatching { Json.decodeFromString<String>(source.substring(start, i)) }.getOrNull()
                    var colon = i; while (colon < source.length && source[colon].isWhitespace()) colon++
                    if (depth == 1 && key == name && source.getOrNull(colon) == ':') {
                        var valueStart = colon + 1; while (source[valueStart].isWhitespace()) valueStart++
                        var end = valueStart; var nested = 0; var quoted = false
                        while (end < source.length) {
                            val c = source[end]
                            if (quoted) { if (c == '\\') { end += 2; continue }; if (c == '"') quoted = false }
                            else when (c) { '"' -> quoted = true; '[', '{' -> nested++; ']', '}' -> if (nested == 0) break else nested--; ',' -> if (nested == 0) break }
                            end++
                        }
                        while (end > valueStart && source[end - 1].isWhitespace()) end--
                        return valueStart until end
                    }
                }
                else -> i++
            }
        }
        return null
    }
    fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    private fun inlineDestinationStart(raw: String): Int? {
        var depth = 0; var i = if (raw.startsWith("!")) 1 else 0
        while (i < raw.length) {
            when (raw[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { i++; return if (raw.getOrNull(i) == '(') i + 1 else null } }
            }
            i++
        }
        return null
    }
    private fun destinationRange(raw: String, from: Int): IntRange? {
        var start = from; while (raw.getOrNull(start)?.isWhitespace() == true) start++
        val angle = raw.getOrNull(start) == '<'; if (angle) start++
        var i = start; var depth = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) { i += 2; continue }
            if (angle && c == '>') break
            if (!angle) {
                if (c == '(') depth++
                if (c == ')') { if (depth == 0) break; depth-- }
                if (c.isWhitespace() && depth == 0) break
            }
            i++
        }
        return if (i > start) start until i else null
    }
    private fun htmlTarget(value: String): String = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    fun encode(value: String): String = value.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    fun normalizedPath(path: String): String? {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex); else -> parts += it } }
        return parts.joinToString("/")
    }
}
