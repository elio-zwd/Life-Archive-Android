package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "stage_summary_drafts",
    foreignKeys = [
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["issueId", "stageId"], unique = true),
        Index(value = ["stageId", "issueId"])
    ]
)
data class StageSummaryDraftEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String,
    val content: String,
    val revisionNumber: Int,
    val createdAt: Long,
    val updatedAt: Long
)
