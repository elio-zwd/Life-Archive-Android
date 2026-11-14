package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "skill_knowledge_usage_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id", "issueId", "stageId"],
            childColumns = ["runId", "issueId", "stageId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["id", "issueId"], unique = true),
        Index(value = ["issueId"]),
        Index(value = ["stageId", "issueId"]),
        Index(value = ["runId", "issueId", "stageId"]),
        Index(value = ["sourceSkillId", "documentId"]),
        Index(value = ["runId", "sourceSkillId", "documentId"], unique = true)
    ]
)
data class SkillKnowledgeUsageSnapshotEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String,
    val runId: String? = null,
    val sourceSkillId: String,
    val documentId: String,
    val titleSnapshot: String,
    val relativePathSnapshot: String,
    val contentSnapshot: String,
    val contentHash: String,
    val userConfirmedAt: Long,
    val createdAt: Long
)
