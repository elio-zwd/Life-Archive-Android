package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "execution_message_usage_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = Message::class,
            parentColumns = ["id"],
            childColumns = ["sourceMessageId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["runId"]),
        Index(value = ["sourceMessageId"]),
        Index(value = ["runId", "sourceMessageId"], unique = true),
        Index(value = ["runId", "usageOrder"], unique = true),
        Index(value = ["sourceExecutionRunId"]),
        Index(value = ["sourceParticipantSnapshotId"]),
    ],
)
data class ExecutionMessageUsageSnapshotEntity(
    @PrimaryKey val id: String,
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
