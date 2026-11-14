package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "artifact_material_sources",
    primaryKeys = ["artifactId", "materialUsageSnapshotId"],
    foreignKeys = [
        ForeignKey(
            entity = ConfirmedArtifactEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["artifactId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MaterialUsageSnapshotEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["materialUsageSnapshotId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["artifactId", "issueId"]),
        Index(value = ["materialUsageSnapshotId", "issueId"])
    ]
)
data class ArtifactMaterialSourceEntity(
    val artifactId: String,
    val issueId: String,
    val materialUsageSnapshotId: String,
    val createdAt: Long
)
