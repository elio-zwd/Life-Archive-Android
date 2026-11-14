package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "execution_participant_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["runId"]),
        Index(value = ["runId", "position"], unique = true),
        Index(value = ["runId", "sourceType", "sourceId"], unique = true),
        Index(value = ["id", "runId"], unique = true),
    ],
)
data class ExecutionParticipantSnapshotEntity(
    @PrimaryKey val id: String,
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
