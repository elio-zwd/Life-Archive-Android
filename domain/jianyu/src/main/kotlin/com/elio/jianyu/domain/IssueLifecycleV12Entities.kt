package com.elio.jianyu.domain

enum class IssueRelationType(val storageValue: String) {
    CONTINUATION("continuation"),
}

enum class IssuePurgeState(val storageValue: String) {
    REQUESTED("requested"),
    WAITING_FOR_TASKS("waiting_for_tasks"),
    CANCELING_TASKS("canceling_tasks"),
    DELETING_FILES("deleting_files"),
    READY_FOR_DATABASE_PURGE("ready_for_database_purge"),
    DATABASE_PURGING("database_purging"),
    FAILED_RETRYABLE("failed_retryable"),
    COMPLETED("completed"),
}

enum class IssuePurgeFailurePhase(val storageValue: String) {
    IMPACT("impact"),
    TASK_CANCEL("task_cancel"),
    AUDIO_DELETE_REQUEST("audio_delete_request"),
    FILE_DELETE("file_delete"),
    DATABASE_PURGE("database_purge"),
    STORAGE("storage"),
}


data class IssueArchiveEventEntity(
     val id: String,
    val issueId: String,
    val archiveOperationId: String,
    val payloadHash: String,
    val summaryMarkdown: String,
    val currentStageIdSnapshot: String?,
    val stageCountSnapshot: Int,
    val runCountSnapshot: Int,
    val draftCountSnapshot: Int,
    val artifactCountSnapshot: Int,
    val audioAssetCountSnapshot: Int,
    val archivedAt: Long,
    val createdAt: Long,
)


data class IssueResumeEventEntity(
     val id: String,
    val issueId: String,
    val archiveEventId: String,
    val resumeOperationId: String,
    val payloadHash: String,
    val changeNote: String,
    val resumedAt: Long,
    val createdAt: Long,
)


data class IssueRelationEntity(
     val id: String,
    val sourceIssueId: String?,
    val targetIssueId: String,
    val sourceArchiveEventId: String?,
    val operationId: String,
    val payloadHash: String,

    val relationType: IssueRelationType = IssueRelationType.CONTINUATION,
    val createdAt: Long,
    val sourcePurgedAt: Long? = null,
)


data class IssuePurgeOperationEntity(
     val id: String,
    val issueId: String,
    val operationId: String,
    val payloadHash: String,
    val impactHash: String?,

    val state: IssuePurgeState = IssuePurgeState.REQUESTED,
    val requestedAt: Long,
    val startedAt: Long? = null,
    val updatedAt: Long,
    val failedAt: Long? = null,
    val failureCode: String? = null,
    val failurePhase: IssuePurgeFailurePhase? = null,

    val retryCount: Int = 0,
)
