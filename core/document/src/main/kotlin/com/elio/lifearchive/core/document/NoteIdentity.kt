package com.elio.lifearchive.core.document

import java.util.UUID
import kotlinx.serialization.json.*

/** 同步标识只改自身字段；不经过 YAML 序列化器重排用户文档。 */
object NoteIdentity {
    const val FIELD = "life_archive_id"
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun valid(value: String) = uuid.matches(value)
    private val key = Regex("(?m)^(?:life_archive_id|\"life_archive_id\"|'life_archive_id')[ \\t]*:([^\\r\\n]*)")
    private fun body(raw: String): String? {
        val range = DocumentAnalyzer.yaml(raw)
        require(range != null || !Regex("\\A\\uFEFF?---(?:\\r?\\n|$)").containsMatchIn(raw)) { "YAML 尚未闭合" }
        if (range == null) return null
        return raw.substring(raw.indexOf('\n') + 1, range.first + Regex("(?m)^(?:---|\\.\\.\\.)[ \\t]*\\r?$").findAll(raw.substring(range)).last().range.first)
    }
    fun read(raw: String): String? {
        val text = body(raw) ?: return null
        val first = text.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith('#') }
        if (text.trimStart().startsWith('{')) {
            val obj = Json.parseToJsonElement(text).jsonObject
            require(DocumentAnalyzer.jsonMembers(text, FIELD).size <= 1) { "重复同步 ID 字段" }
            val value = obj[FIELD] ?: return null
            require(value is JsonPrimitive && value.isString && valid(value.content)) { "同步 ID 必须为小写 UUID" }
            return value.content
        }
        require(first == null || (!first.startsWith(' ') && !first.startsWith('\t') && Regex("^[^:]+:").containsMatchIn(first))) { "无法安全修改此 YAML 结构" }
        val matches = key.findAll(text).toList()
        require(matches.size <= 1) { "重复同步 ID 字段" }
        val match = matches.singleOrNull() ?: return null
        val value = DocumentAnalyzer.unquote(DocumentAnalyzer.withoutComment(match.groupValues[1]).trim())
        require(valid(value)) { "同步 ID 必须为小写 UUID" }
        return value
    }
    fun ensure(raw: String, fallback: String = UUID.randomUUID().toString()): String {
        require(valid(fallback))
        if (read(raw) != null) return raw
        val nl = if (raw.contains("\r\n")) "\r\n" else "\n"
        val text = body(raw)
        if (text == null) {
            val bom = if (raw.startsWith('\uFEFF')) "\uFEFF" else ""
            return bom + "---${nl}$FIELD: $fallback${nl}---$nl" + raw.removePrefix(bom)
        }
        val at = raw.indexOf('\n') + 1
        if (text.trimStart().startsWith('{')) {
            val start = text.indexOf('{') + 1
            val comma = if (Json.parseToJsonElement(text).jsonObject.isEmpty()) "" else ","
            return raw.substring(0, at + start) + "\"$FIELD\":\"$fallback\"$comma" + raw.substring(at + start)
        }
        return raw.substring(0, at) + "$FIELD: $fallback$nl" + raw.substring(at)
    }
}

fun isSyncPath(path: String): Boolean = LibraryPath.isValid(path) &&
    path !in setOf("AGENTS.md", ".gitignore") &&
    path.split('/').none { it.lowercase(java.util.Locale.ROOT) in setOf(".git", ".obsidian", ".life-archive") }
