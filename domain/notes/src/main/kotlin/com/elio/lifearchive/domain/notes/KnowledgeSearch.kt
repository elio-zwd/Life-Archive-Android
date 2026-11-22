package com.elio.lifearchive.domain.notes

data class KnowledgeQuery(
    val text: String,
    val topic: String? = null,
    val kind: NoteKind? = null,
    val tag: String? = null,
)

data class KnowledgeHit(
    val note: NoteSummary,
    val title: String,
    val excerpt: String,
    val offset: Int?,
)

data class KnowledgeListing(
    val hits: List<KnowledgeHit>,
    val total: Int,
    val topics: List<String>,
    val tags: List<String>,
    val pendingIndex: Int,
)
