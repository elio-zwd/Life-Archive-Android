package com.elio.lifearchive.core.document

import java.net.URI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 仅提取分享文字中的原始地址，不展开短链、不规范化查询参数。 */
fun extractSharedWebLinks(text: String): List<String> = Regex("https?://[^\\s\\p{Z}<>\"'`，。！？；、【】《》「」『』]+", RegexOption.IGNORE_CASE)
    .findAll(text).mapNotNull { match ->
        var url = match.value
        do {
            val before = url
            url = url.trimEnd('.', ',', ';', '!', '，', '。', '！', '？', '；', '、')
            for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}', '（' to '）')) {
                while (url.endsWith(close) && url.count { it == close } > url.count { it == open }) url = url.dropLast(1)
            }
        } while (url != before)
        url.takeIf(::isWebLink)
    }.distinct().toList()

fun isWebLink(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && value.none { it.isWhitespace() || Character.isSpaceChar(it) }
}.getOrDefault(false)

/** 对 source 单字段做保留原文的修改；不能安全定位时拒绝写入。 */
object NoteSource {
    private const val unsupported = "该 source 结构不能安全修改，请在源码中编辑"
    private val key = Regex("(?m)^(?:source[ \\t]*|\"source\"|'source')[ \\t]*:([^\\r\\n]*)")

    fun read(raw: String): String? {
        val range = DocumentAnalyzer.yaml(raw) ?: return null
        val body = raw.substring(range).substringAfter('\n').replace(Regex("\\r?\\n(?:---|\\.\\.\\.)(?:\\r?\\n)?$"), "")
        if (body.trimStart().startsWith('{')) {
            val value = Json.parseToJsonElement(body).jsonObject["source"] ?: return null
            require(value is JsonPrimitive && (value.isString || value is JsonNull)) { unsupported }
            return value.contentOrNull
        }
        val fields = key.findAll(body).toList()
        require(fields.size <= 1) { unsupported }
        return fields.singleOrNull()?.let { scalar(it.groupValues[1]) }
    }

    fun withUrl(raw: String, url: String): String {
        require(isWebLink(url)) { "请提供完整的 HTTP 或 HTTPS 链接" }
        val newline = if (raw.contains("\r\n")) "\r\n" else "\n"
        val encoded = Json.encodeToString(url)
        val range = DocumentAnalyzer.yaml(raw)
        if (range == null) {
            require(!Regex("\\A\\uFEFF?---(?:\\r?\\n|$)").containsMatchIn(raw)) { "YAML 尚未闭合，请先在源码中修正" }
            val bom = if (raw.startsWith('\uFEFF')) "\uFEFF" else ""
            return bom + "---${newline}source: $encoded${newline}---$newline" + raw.removePrefix(bom)
        }
        val start = raw.indexOf('\n') + 1
        val end = range.first + Regex("(?m)^(---|\\.\\.\\.)[ \\t]*\\r?$").findAll(raw.substring(range)).last().range.first
        val body = raw.substring(start, end)
        if (body.trimStart().startsWith('{')) {
            val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { throw IllegalArgumentException(unsupported) }
            val spans = DocumentAnalyzer.jsonMembers(body, "source")
            require(spans.size <= 1) { unsupported }
            val span = spans.singleOrNull()
            if (span != null) {
                val value = obj["source"]
                require(value is JsonPrimitive && (value.isString || value is JsonNull)) { unsupported }
            }
            val changed = if (span != null) body.replaceRange(span, encoded) else {
                val at = body.indexOf('{') + 1
                body.substring(0, at) + "\"source\":$encoded" + (if (obj.isEmpty()) "" else ",") + body.substring(at)
            }
            return raw.substring(0, start) + changed + raw.substring(end)
        }
        // 仅处理常规顶层映射，避免把缩进映射或流式 YAML 误当作缺字段。
        val first = body.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith('#') }
        require(first == null || (!first.startsWith(' ') && !first.startsWith('\t') && Regex("^[^:]+:").containsMatchIn(first))) { unsupported }
        val fields = key.findAll(body).toList()
        require(fields.size <= 1) { unsupported }
        val field = fields.singleOrNull()
        if (field == null) return raw.substring(0, start) + "source: $encoded$newline" + raw.substring(start)
        scalar(field.groupValues[1])
        val after = body.substring(field.range.last + 1).lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith('#') }
        require(after == null || (!after.startsWith(' ') && !after.startsWith('\t') && !after.startsWith('-'))) { unsupported }
        val rest = field.groupValues[1]
        val leading = rest.takeWhile { it == ' ' || it == '\t' }
        val value = DocumentAnalyzer.withoutComment(rest).trimEnd()
        var suffix = if (value.isBlank()) rest.drop(leading.length) else rest.substring(value.length)
        if (suffix.startsWith('#')) suffix = " $suffix"
        val changed = field.value.dropLast(rest.length) + leading.ifEmpty { " " } + encoded + suffix
        return raw.replaceRange(start + field.range.first, start + field.range.last + 1, changed)
    }

    private fun scalar(raw: String): String {
        val value = DocumentAnalyzer.withoutComment(raw).trim()
        return when {
            value.isEmpty() || value in listOf("null", "~") -> ""
            value.startsWith('"') -> runCatching { Json.decodeFromString<String>(value) }.getOrElse { throw IllegalArgumentException(unsupported) }
            value.startsWith('\'') -> {
                require(Regex("'(?:''|[^'])*'").matches(value)) { unsupported }
                DocumentAnalyzer.unquote(value)
            }
            else -> {
                require(value.first() !in "|>&*!{[?`" && !Regex(":(?:\\s|$)").containsMatchIn(value)) { unsupported }
                value
            }
        }
    }
}
