package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "issue_resume_events",
    foreignKeys = [
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = IssueArchiveEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["archiveEventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["issueId"]),
        Index(value = ["archiveEventId"]),
        Index(value = ["resumeOperationId"], unique = true),
        Index(value = ["issueId", "resumedAt"]),
    ],
)
data class IssueResumeEventEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val archiveEventId: String,
    val resumeOperationId: String,
    val payloadHash: String,
    val changeNote: String,
    val resumedAt: Long,
    val createdAt: Long,
)
