package com.elio.jianyu.domain

enum class LifeNoteKind { PERSONAL, VIDEO }

data class LifeKnowledgeQuery(
    val text: String = "",
    val topic: String? = null,
    val kind: LifeNoteKind? = null,
    val tag: String? = null,
)

data class LifeKnowledgeHit(
    val noteId: String,
    val version: String,
    val title: String,
    val path: String,
    val excerpt: String,
    val offset: Int?,
)

sealed interface LifeKnowledgeResult {
    data class Found(
        val hits: List<LifeKnowledgeHit>,
        val total: Int,
        val topics: List<String>,
        val tags: List<String>,
        val pendingIndex: Int,
    ) : LifeKnowledgeResult
    data object Unavailable : LifeKnowledgeResult
}

enum class OpenLifeNote { OPENED, MISSING, UNAVAILABLE }

/** 见域只接收本地检索结果和原文导航能力，不持有笔记存储。 */
interface LifeKnowledge {
    suspend fun search(query: LifeKnowledgeQuery): LifeKnowledgeResult
    suspend fun open(hit: LifeKnowledgeHit): OpenLifeNote
}
