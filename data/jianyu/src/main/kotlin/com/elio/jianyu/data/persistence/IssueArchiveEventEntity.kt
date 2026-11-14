package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "issue_archive_events",
    foreignKeys = [
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["issueId"]),
        Index(value = ["archiveOperationId"], unique = true),
        Index(value = ["issueId", "archivedAt"]),
    ],
)
data class IssueArchiveEventEntity(
    @PrimaryKey val id: String,
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
