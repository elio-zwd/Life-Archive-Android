package com.elio.jianyu.domain

enum class ExecutionParticipantStatus(val storageValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    STREAMING("streaming"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    TIMED_OUT("timed_out"),
    STOPPED("stopped"),
    RETRYABLE("retryable"),
}


data class ExecutionParticipantStateEntity(
     val participantSnapshotId: String,
    val runId: String,

    val status: ExecutionParticipantStatus = ExecutionParticipantStatus.QUEUED,

    val attemptCount: Int = 0,
    val outputMessageId: Long? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,

    val hasIncompleteOutput: Boolean = false,
    val updatedAt: Long,
)


data class ExecutionRunBudgetEntity(
     val rootRunId: String,

    val usedApiCalls: Int = 0,
    val maxCharacters: Int,
    val maxSearchQueriesPerCharacter: Int,
    val maxOutputTokensPerAnswer: Int,

    val closed: Boolean = false,
    val updatedAt: Long,
)
