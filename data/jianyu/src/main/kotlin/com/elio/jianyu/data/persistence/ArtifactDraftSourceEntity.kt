package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "artifact_draft_sources",
    primaryKeys = ["artifactId", "draftRevisionId"],
    foreignKeys = [
        ForeignKey(
            entity = ConfirmedArtifactEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["artifactId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = StageSummaryDraftRevisionEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["draftRevisionId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["artifactId", "issueId"]),
        Index(value = ["draftRevisionId", "issueId"])
    ]
)
data class ArtifactDraftSourceEntity(
    val artifactId: String,
    val issueId: String,
    val draftRevisionId: String,
    val createdAt: Long
)
