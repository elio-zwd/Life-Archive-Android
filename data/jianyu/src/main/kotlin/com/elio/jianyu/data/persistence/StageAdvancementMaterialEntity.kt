package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "stage_advancement_materials",
    primaryKeys = ["stageId", "materialReferenceId"],
    foreignKeys = [
        ForeignKey(
            entity = StageAdvancementEntity::class,
            parentColumns = ["stageId", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MaterialReferenceEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["materialReferenceId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stageId", "issueId"]),
        Index(value = ["materialReferenceId", "issueId"]),
        Index(value = ["stageId", "position"], unique = true),
    ],
)
data class StageAdvancementMaterialEntity(
    val stageId: String,
    val issueId: String,
    val materialReferenceId: String,
    val position: Int,
    val inheritedAt: Long,
)
