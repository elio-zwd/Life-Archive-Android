package com.elio.lifearchive.core.document

import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class ContentTimes(val created: String? = null, val updated: String? = null)

/** 只处理本项目的两个标量字段；未知 YAML 和正文保持原字节顺序。 */
object NoteTimes {
    const val CREATED = "life_archive_created_at"
    const val UPDATED = "life_archive_updated_at"
    fun valid(value: String): Boolean = runCatching {
        require(Regex("\\d{4}-\\d{2}-\\d{2}(?:T(?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d(?:\\.\\d{1,9})?)?(?:Z|[+-](?:0\\d|1[0-8]):[0-5]\\d))?").matches(value))
        if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) LocalDate.parse(value) else OffsetDateTime.parse(value)
    }.isSuccess
    fun epoch(value: String?): Long? = value?.let { runCatching {
        if (it.length == 10) LocalDate.parse(it).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        else OffsetDateTime.parse(it).toInstant().toEpochMilli()
    }.getOrNull() }
    private fun section(raw: String): Pair<Int, Int>? {
        val range = DocumentAnalyzer.yaml(raw)
        require(range != null || !Regex("\\A\\uFEFF?---(?:\\r?\\n|$)").containsMatchIn(raw)) { "YAML 尚未闭合" }
        if (range == null) return null
        val start = raw.indexOf('\n') + 1
        val end = Regex("(?m)^(---|\\.\\.\\.)[ \\t]*\\r?$").findAll(raw.substring(range)).last().range.first
        return start to end
    }
    private fun key(name: String) = Regex("(?m)^(?:$name|\"$name\"|'$name')[ \\t]*:([^\\r\\n]*)")
    private fun value(raw: String, name: String): String? {
        val (start, end) = section(raw) ?: return null
        val body = raw.substring(start, end)
        val result = if (body.trimStart().startsWith('{')) {
            require(DocumentAnalyzer.jsonMembers(body, name).size <= 1) { "重复时间字段：$name" }
            val item = Json.parseToJsonElement(body).jsonObject[name] ?: return null
            require(item is JsonPrimitive && item.isString) { "时间必须为日期或带时区时间" }
            item.content
        } else {
            val matches = key(name).findAll(body).toList()
            require(matches.size <= 1) { "重复时间字段：$name" }
            val field = matches.singleOrNull() ?: return null
            val after = body.substring(field.range.last + 1).lineSequence().drop(1).firstOrNull { it.isNotBlank() && !it.trimStart().startsWith('#') }
            require(after == null || !after.first().isWhitespace() && !after.startsWith('-')) { "时间字段包含不支持的续行" }
            DocumentAnalyzer.unquote(DocumentAnalyzer.withoutComment(field.groupValues[1]).trim())
        }
        require(valid(result)) { "无效时间字段：$name" }
        return result
    }
    fun read(raw: String) = ContentTimes(value(raw, CREATED), value(raw, UPDATED))
    fun withTimes(raw: String, times: ContentTimes): String {
        val existing = read(raw)
        require(times.created == null || valid(times.created))
        require(times.updated == null || valid(times.updated))
        val created = times.created ?: existing.created
        val updated = times.updated ?: existing.updated
        if (created != null && updated != null) require(if ('T' in created && 'T' in updated) epoch(updated)!! >= epoch(created)!! else updated.take(10) >= created.take(10)) { "修改时间不能早于写录时间" }
        var result = raw
        for ((name, time) in listOf(CREATED to times.created, UPDATED to times.updated)) {
            if (time == null || value(result, name) == time) continue
            result = patch(result, name, time)
        }
        return result
    }
    private fun patch(raw: String, name: String, value: String): String {
        val encoded = Json.encodeToString(value)
        val nl = if (raw.contains("\r\n")) "\r\n" else "\n"
        val bounds = section(raw)
        if (bounds == null) {
            val bom = if (raw.startsWith('\uFEFF')) "\uFEFF" else ""
            return bom + "---$nl$name: $encoded${nl}---$nl" + raw.removePrefix(bom)
        }
        val (start, end) = bounds
        val body = raw.substring(start, end)
        if (body.trimStart().startsWith('{')) {
            val obj = Json.parseToJsonElement(body).jsonObject
            val span = DocumentAnalyzer.jsonMembers(body, name).singleOrNull()
            val changed = if (span != null) body.replaceRange(span, encoded) else {
                val at = body.indexOf('{') + 1
                body.substring(0, at) + "\"$name\":$encoded" + (if (obj.isEmpty()) "" else ",") + body.substring(at)
            }
            return raw.substring(0, start) + changed + raw.substring(end)
        }
        val first = body.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith('#') }
        require(first == null || !first.first().isWhitespace() && Regex("^[^:]+:").containsMatchIn(first)) { "无法安全修改 YAML" }
        val field = key(name).find(body)
        if (field == null) return raw.substring(0, start) + "$name: $encoded$nl" + raw.substring(start)
        val rest = field.groupValues[1]
        val prefix = rest.takeWhile { it == ' ' || it == '\t' }.ifEmpty { " " }
        val plain = DocumentAnalyzer.withoutComment(rest)
        val suffix = rest.substring(plain.length)
        return raw.replaceRange(start + field.range.first, start + field.range.last + 1, field.value.substringBefore(':') + ":" + prefix + encoded + suffix)
    }
    /** 技术字段和属性标签变化不算内容编辑，其他 YAML 字段仍有内容含义。 */
    fun content(raw: String, ignoreTags: Boolean = true): String {
        val (start, end) = section(raw) ?: return "\u0000" + raw.removePrefix("\uFEFF")
        val body = raw.substring(start, end)
        val ignored = setOf(NoteIdentity.FIELD, CREATED, UPDATED) + if (ignoreTags) setOf("tags") else emptySet()
        val metadata = if (body.trimStart().startsWith('{')) JsonObject(Json.parseToJsonElement(body).jsonObject.filterKeys { it !in ignored }).toString()
        else {
            var skipping = false
            body.lineSequence().filter { line ->
                val field = Regex("^(?:['\"]?)([A-Za-z_][A-Za-z0-9_]*)(?:['\"]?)[ \\t]*:").find(line)?.groupValues?.get(1)
                if (field != null) skipping = field in ignored
                else if (line.isNotBlank() && !line.first().isWhitespace() && !line.startsWith('-') && !line.startsWith('#')) skipping = false
                !skipping && line.isNotBlank() && !line.trimStart().startsWith('#')
            }.joinToString("\n")
        }
        return metadata + "\u0000" + raw.substring(DocumentAnalyzer.yaml(raw)!!.last + 1)
    }
}
