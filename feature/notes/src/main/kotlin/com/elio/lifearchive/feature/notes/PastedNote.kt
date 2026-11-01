package com.elio.lifearchive.feature.notes

import com.elio.lifearchive.core.document.DocumentAnalyzer
import com.elio.lifearchive.core.document.LibraryPath
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

internal data class PastedNote(val name: String, val body: String, val tags: List<String>, val warning: String?)

/** 仅在新建边界推导文件名；已有 frontmatter 和正文不经过重新序列化。 */
internal fun parsePastedNote(text: String, folder: String?, now: LocalDateTime = LocalDateTime.now()): PastedNote {
    val body = unwrapMarkdown(text)
    require(body.isNotBlank()) { "粘贴内容为空" }
    val analysis = runCatching { DocumentAnalyzer.analyze(body) }.getOrNull()
    val yaml = DocumentAnalyzer.yaml(body)
    val suspiciousYaml = yaml == null && Regex("\\A\\uFEFF?---\\r?\\n").containsMatchIn(body)
    val fallback = "粘贴记录-${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))}"
    val title = analysis?.title?.takeIf { it.isNotBlank() }
        ?: analysis?.headings?.firstOrNull { it.level == 1 }?.name?.takeIf { it.isNotBlank() }
        ?: if (suspiciousYaml) null else body.substring((yaml?.last?.plus(1)) ?: 0).lineSequence()
            .firstOrNull { it.isNotBlank() }?.let(::plainName)?.let { line ->
                line.substring(0, line.offsetByCodePoints(0, minOf(40, line.codePointCount(0, line.length))))
            }?.takeIf { it.isNotBlank() }
    val name = pastedFileName(title ?: fallback, folder)
    val warning = when {
        suspiciousYaml -> "疑似未闭合 YAML，已保留原文，未追加属性；可在下方修正后保存。"
        analysis == null -> "部分属性识别失败，已保留原文。"
        else -> null
    }
    val enriched = if (yaml != null || suspiciousYaml) body else {
        val newline = if (body.contains("\r\n")) "\r\n" else "\n"
        val bom = if (body.startsWith('\uFEFF')) "\uFEFF" else ""
        bom + listOf("---", "title: ${Json.encodeToString(title ?: fallback)}", "tags: []", "---", "").joinToString(newline) + body.removePrefix("\uFEFF")
    }
    return PastedNote(name, enriched, analysis?.tags.orEmpty(), warning)
}

private fun plainName(value: String): String = value
    .replace(Regex("!?\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("\\[\\[([^]|]+)(?:\\|([^]]+))?]]")) { it.groupValues[2].ifBlank { it.groupValues[1] } }
    .replace(Regex("^[ \\t]*(?:#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)"), "")
    .replace(Regex("[*`~]|=="), "").trim()

internal fun pastedFileName(title: String, folder: String?, suffix: String = ""): String {
    var base = plainName(title).removeSuffix(".md").replace(Regex("[\\p{Cc}\\\\/:*?\"<>|]"), "-").trim().trimEnd('.')
    if (base.isBlank()) base = "粘贴记录"
    return fitPastedFileName(base, folder, suffix)
}

/** 文件名一经用户编辑，不再按 Markdown 清理；添加后缀时仅裁剪到可用路径长度。 */
internal fun fitPastedFileName(name: String, folder: String?, suffix: String = ""): String {
    val limit = minOf(117, 240 - (folder?.length?.plus(1) ?: 0) - 3) - suffix.length
    require(limit >= 1) { "当前笔记本路径过长，无法创建笔记" }
    var base = name
    if (!LibraryPath.isValid(base.take(limit) + suffix + ".md")) base = "笔记-$base"
    // 不在 UTF-16 代理对中间截断，避免损坏 emoji 的 UTF-8 写入。
    base = base.take(limit).trimEnd(' ', '.')
    if (base.lastOrNull()?.isHighSurrogate() == true) base = base.dropLast(1)
    return base.ifBlank { "记" } + suffix
}

private fun unwrapMarkdown(text: String): String {
    val match = Regex("\\A\\s*(`{3,}|~{3,})(?:markdown|md)[ \\t]*\\r?\\n([\\s\\S]*)\\r?\\n\\1[ \\t]*\\s*\\z", RegexOption.IGNORE_CASE).matchEntire(text)
        ?: return text
    val fence = match.groupValues[1]
    val inside = match.groupValues[2]
    // 同类且不短于外层的围栏会提前结束代码块，此时不能把全文当作单个包装。
    val closing = Regex("(?m)^ {0,3}${Regex.escape(fence.first().toString())}{${fence.length},}[ \\t]*\\r?$")
    return if (closing.containsMatchIn(inside)) text else inside
}
