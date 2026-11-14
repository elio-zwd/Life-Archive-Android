package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "execution_runs",
    foreignKeys = [
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["retryOfRunId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stageId", "issueId"]),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["id", "issueId"], unique = true),
        Index(value = ["id", "issueId", "stageId"], unique = true),
        Index(value = ["triggerMessageId"]),
        Index(value = ["retryOfRunId"]),
        Index(value = ["parentRunId"]),
        Index(value = ["discussionId"]),
        Index(value = ["stageId", "runKind"]),
    ],
)
data class ExecutionRunEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String,
    val triggerMessageId: Long? = null,
    val idempotencyKey: String,
    @ColumnInfo(defaultValue = "'not_started'")
    val status: ExecutionRunStatus = ExecutionRunStatus.NOT_STARTED,
    val retryOfRunId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val stoppedAt: Long? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    @ColumnInfo(defaultValue = "'standard'")
    val runKind: ExecutionRunKind = ExecutionRunKind.STANDARD,
    val parentRunId: String? = null,
    val discussionId: String? = null,
    @ColumnInfo(defaultValue = "'full_stage'")
    val historyScope: ExecutionHistoryScope = ExecutionHistoryScope.FULL_STAGE,
    val actualModelId: String,
    val actualThinkingLevel: ExecutionThinkingLevel,
    val thinkingLevelSource: ExecutionThinkingSource,
)
