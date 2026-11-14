package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "confirmed_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ConfirmedArtifactEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionOfArtifactId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["id", "issueId"], unique = true),
        Index(value = ["id", "issueId", "stageId"], unique = true),
        Index(value = ["stageId", "issueId"]),
        Index(value = ["revisionOfArtifactId"]),
        Index(value = ["confirmedAt"])
    ]
)
data class ConfirmedArtifactEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String,
    val title: String,
    val content: String,
    val artifactType: String,
    val contentFormat: String,
    val confirmedAt: Long,
    val revisionOfArtifactId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
