package com.elio.jianyu.viewmodel

import com.elio.jianyu.domain.ContextSourceType

data class FormalConversationContext(
    val issueId: String,
    val stageId: String,
)

data class ConversationContextSelection(
    val sourceType: ContextSourceType,
    val sourceId: String,
    val title: String,
    val content: String,
    val sourceKind: String = "",
    val sourceLocator: String? = null,
    val expectedSourceHash: String,
    val expectedSourceUpdatedAt: Long,
    val confirmationOrder: Int,
    val networkAllowed: Boolean,
    val sensitive: Boolean,
    val sensitiveConfirmed: Boolean,
    val confirmedAt: Long = 0L,
)

data class RetryableRoundtableState(
    val sessionId: Long,
    val questionRunId: Long,
    val characterIds: List<String>
)

enum class RoundActionState {
    CONTINUE_ROUND,
    START_NEXT_ROUND,
}
