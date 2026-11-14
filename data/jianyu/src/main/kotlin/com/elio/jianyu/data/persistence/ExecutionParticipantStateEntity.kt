package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "execution_participant_states",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionParticipantSnapshotEntity::class,
            parentColumns = ["id", "runId"],
            childColumns = ["participantSnapshotId", "runId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = Message::class,
            parentColumns = ["id"],
            childColumns = ["outputMessageId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["participantSnapshotId", "runId"]),
        Index(value = ["runId"]),
        Index(value = ["runId", "status"]),
        Index(value = ["outputMessageId"]),
    ],
)
data class ExecutionParticipantStateEntity(
    @PrimaryKey val participantSnapshotId: String,
    val runId: String,
    @ColumnInfo(defaultValue = "'queued'")
    val status: ExecutionParticipantStatus = ExecutionParticipantStatus.QUEUED,
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,
    val outputMessageId: Long? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    @ColumnInfo(defaultValue = "0")
    val hasIncompleteOutput: Boolean = false,
    val updatedAt: Long,
)
