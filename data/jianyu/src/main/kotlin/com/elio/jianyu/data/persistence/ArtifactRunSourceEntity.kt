package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "artifact_run_sources",
    primaryKeys = ["artifactId", "runId"],
    foreignKeys = [
        ForeignKey(
            entity = ConfirmedArtifactEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["artifactId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["runId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["artifactId", "issueId"]),
        Index(value = ["runId", "issueId"])
    ]
)
data class ArtifactRunSourceEntity(
    val artifactId: String,
    val issueId: String,
    val runId: String,
    val createdAt: Long
)
