package com.elio.jianyu.domain


data class IssueEntity(
     val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val legacyChatSessionId: Long? = null,

    val defaultThinkingPolicy: IssueThinkingPolicy = IssueThinkingPolicy.AUTO,
)

enum class IssueThinkingPolicy(val storageValue: String) {
    AUTO("auto"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

enum class ExecutionThinkingLevel(val storageValue: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

enum class ExecutionThinkingSource(val storageValue: String) {
    ROUND_USER_OVERRIDE("round_user_override"),
    ISSUE_USER_DEFAULT("issue_user_default"),
    AUTO_ROUTED("auto_routed"),
}


data class StageEntity(
     val id: String,
    val issueId: String,
    val sequenceIndex: Int,
    val title: String,
    val objective: String,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ExecutionRunStatus(val storageValue: String) {
    NOT_STARTED("not_started"),
    RUNNING("running"),
    PARTIAL_SUCCESS("partial_success"),
    SUCCEEDED("succeeded"),
    STOPPED("stopped"),
    FAILED("failed"),
    RETRYABLE("retryable"),
    COMPLETED("completed"),
}

enum class ExecutionRunKind(val storageValue: String) {
    STANDARD("standard"),
    DIRECTED_RESPONSE("directed_response"),
    CROSS_DISCUSSION_RESPONSE("cross_discussion_response"),
    CROSS_DISCUSSION_SYNTHESIS("cross_discussion_synthesis"),
}

enum class ExecutionHistoryScope(val storageValue: String) {
    FULL_STAGE("full_stage"),
    EXPLICIT_MESSAGES("explicit_messages"),
    NO_HISTORY("no_history"),
}


data class ExecutionRunEntity(
     val id: String,
    val issueId: String,
    val stageId: String,
    val triggerMessageId: Long? = null,
    val idempotencyKey: String,

    val status: ExecutionRunStatus = ExecutionRunStatus.NOT_STARTED,
    val retryOfRunId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val stoppedAt: Long? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,

    val runKind: ExecutionRunKind = ExecutionRunKind.STANDARD,
    val parentRunId: String? = null,
    val discussionId: String? = null,

    val historyScope: ExecutionHistoryScope = ExecutionHistoryScope.FULL_STAGE,
    val actualModelId: String,
    val actualThinkingLevel: ExecutionThinkingLevel,
    val thinkingLevelSource: ExecutionThinkingSource,
)


data class ExecutionParticipantSnapshotEntity(
     val id: String,
    val runId: String,
    val sourceType: String,
    val sourceId: String,
    val displayName: String,
    val avatar: String,
    val skillAssetPath: String,
    val systemPrompt: String,
    val configurationJson: String,
    val defaultResponsibility: String,
    val position: Int,
    val createdAt: Long,
)
