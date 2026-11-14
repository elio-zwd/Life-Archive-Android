package com.elio.jianyu.domain


data class ConversationSummaryCheckpoint(
    val chatId: Long,
    val characterId: String,
    val summary: String,
    val coveredThroughMessageId: Long,
    val sourceFingerprint: String,
    val promptVersion: Int,
    val updatedAt: Long,
)
