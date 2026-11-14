package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "stage_summary_draft_revisions",
    foreignKeys = [
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["id", "issueId"], unique = true),
        Index(value = ["stageId", "issueId"]),
        Index(value = ["issueId", "stageId", "revisionNumber"], unique = true),
        Index(value = ["draftIdSnapshot"])
    ]
)
data class StageSummaryDraftRevisionEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String,
    val draftIdSnapshot: String,
    val revisionNumber: Int,
    val contentSnapshot: String,
    val createdAt: Long
)
