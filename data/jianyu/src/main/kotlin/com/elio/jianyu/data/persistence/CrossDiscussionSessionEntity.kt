package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "cross_discussion_sessions",
    foreignKeys = [
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = Message::class,
            parentColumns = ["id"],
            childColumns = ["triggerMessageId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["responseRunId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["synthesisRunId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stageId", "issueId"]),
        Index(value = ["triggerMessageId"]),
        Index(value = ["responseRunId"], unique = true),
        Index(value = ["synthesisRunId"], unique = true),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["stageId", "status"]),
    ],
)
data class CrossDiscussionSessionEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String,
    val triggerMessageId: Long,
    val responseRunId: String,
    val synthesisRunId: String? = null,
    val integratorSkillId: String,
    val status: CrossDiscussionStatus,
    val idempotencyKey: String,
    @ColumnInfo(defaultValue = "'[]'")
    val successfulParticipantIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "'[]'")
    val failedParticipantIdsJson: String = "[]",
    val partialSynthesisConfirmedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val failureCode: String? = null,
)
