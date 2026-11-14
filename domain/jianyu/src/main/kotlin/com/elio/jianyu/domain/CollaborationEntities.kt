package com.elio.jianyu.domain

enum class CrossDiscussionStatus(val storageValue: String) {
    RESPONDING("responding"),
    PARTIAL_SUCCESS("partial_success"),
    AWAITING_SYNTHESIS("awaiting_synthesis"),
    SYNTHESIZING("synthesizing"),
    SYNTHESIS_RETRYABLE("synthesis_retryable"),
    SUCCEEDED("succeeded"),
    STOPPED("stopped"),
    FAILED("failed"),
}


data class CrossDiscussionSessionEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val triggerMessageId: Long,
    val responseRunId: String,
    val synthesisRunId: String? = null,
    val integratorSkillId: String,
    val status: CrossDiscussionStatus,
    val idempotencyKey: String,

    val successfulParticipantIdsJson: String = "[]",

    val failedParticipantIdsJson: String = "[]",
    val partialSynthesisConfirmedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val failureCode: String? = null,
)


data class ExecutionMessageUsageSnapshotEntity(
     val id: String,
    val runId: String,
    val sourceMessageId: Long,
    val sourceExecutionRunId: String? = null,
    val sourceParticipantSnapshotId: String? = null,
    val senderIdSnapshot: String,
    val senderNameSnapshot: String,
    val contentSnapshot: String,
    val contentHash: String,
    val usageOrder: Int,
    val usedAt: Long,
)
