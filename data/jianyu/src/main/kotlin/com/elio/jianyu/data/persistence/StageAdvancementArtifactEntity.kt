package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "stage_advancement_artifacts",
    primaryKeys = ["stageId", "artifactId"],
    foreignKeys = [
        ForeignKey(
            entity = StageAdvancementEntity::class,
            parentColumns = ["stageId", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ConfirmedArtifactEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["artifactId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stageId", "issueId"]),
        Index(value = ["artifactId", "issueId"]),
        Index(value = ["stageId", "position"], unique = true),
    ],
)
data class StageAdvancementArtifactEntity(
    val stageId: String,
    val issueId: String,
    val artifactId: String,
    val position: Int,
    val inheritedAt: Long,
)
