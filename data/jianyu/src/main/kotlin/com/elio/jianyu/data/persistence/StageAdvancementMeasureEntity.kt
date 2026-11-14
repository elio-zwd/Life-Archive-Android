package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "stage_advancement_measures",
    primaryKeys = ["stageId", "measure"],
    foreignKeys = [
        ForeignKey(
            entity = StageAdvancementEntity::class,
            parentColumns = ["stageId", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stageId", "issueId"]),
        Index(value = ["stageId", "position"], unique = true),
    ],
)
data class StageAdvancementMeasureEntity(
    val stageId: String,
    val issueId: String,
    val measure: StageAdvancementMeasure,
    val position: Int,
)
