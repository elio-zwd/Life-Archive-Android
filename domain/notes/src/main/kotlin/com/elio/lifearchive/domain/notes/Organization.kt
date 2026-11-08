package com.elio.lifearchive.domain.notes

import com.elio.lifearchive.core.document.*

enum class NoteKind(val folder: String) { PERSONAL("个人笔记"), VIDEO("视频摘记") }
enum class TagMatch { ALL, ANY }
enum class NoteGrouping { TOPIC, TAG }
data class NotePlacement(val topic: String? = null, val kind: NoteKind? = null)
object NoteOrganization {
    const val INBOX = "待整理"
    val initialTopics = listOf("职业发展", "沟通表达", "效率工具", "健康管理", "饮食烹饪", "休闲生活", "学习研究", "生活事务")
    fun placement(path: String): NotePlacement {
        val parts = path.split('/')
        val kind = parts.getOrNull(1)?.let { value -> NoteKind.entries.find { it.folder == value } }
        return NotePlacement(parts.firstOrNull()?.takeIf { parts.size >= 3 && kind != null && it != INBOX }, kind)
    }
    fun folder(topic: String?, kind: NoteKind?): String = if (topic == null || kind == null) INBOX + (kind?.let { "/${it.folder}" } ?: "") else "$topic/${kind.folder}"
    fun validTopic(name: String) = LibraryPath.isValid(name) && '/' !in name && name != INBOX && !name.startsWith('.') && name != "_attachments"
    fun validTag(tag: String) = tag.split('/').size in 1..3 && tag.split('/').all { it.isNotBlank() } && tag.none { it.isWhitespace() || it in "#[],:\\" }
    fun matches(actual: String, selected: String) = actual.equals(selected, true) || actual.startsWith("$selected/", true)
    fun tagTree(tags: List<String>) = tags.flatMap { tag -> tag.split('/').indices.map { tag.split('/').take(it + 1).joinToString("/") } }.distinct().sorted()
}

data class OrganizationChange(val id: NoteId, val expected: DocumentVersion, val topic: String?, val kind: NoteKind?, val tags: List<String>? = null, val times: ContentTimes? = null)
data class OrganizationPreviewItem(val change: OrganizationChange, val from: String, val to: String, val beforeTags: List<String>, val afterTags: List<String>, val affectedLinks: List<String>, val issue: String? = null)
data class OrganizationPreview(val items: List<OrganizationPreviewItem>, val versions: Map<NoteId, DocumentVersion>)
data class OrganizationOutcome(val id: NoteId, val status: String, val message: String)
data class OrganizationResult(val outcomes: List<OrganizationOutcome>)
data class OrganizationSuggestion(val note: LibraryNote, val topics: List<String>, val placement: NotePlacement, val reason: String)
data class TaxonomyChange(val from: String, val to: String, val topic: Boolean)
data class TaxonomyPreview(val change: TaxonomyChange, val notes: List<LibraryNote>, val folders: List<String>)
