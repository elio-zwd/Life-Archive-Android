package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "stage_advancements",
    foreignKeys = [
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["sourceStageId", "issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stageId", "issueId"], unique = true),
        Index(value = ["sourceStageId", "issueId"]),
        Index(value = ["operationId"], unique = true),
    ],
)
data class StageAdvancementEntity(
    @PrimaryKey val stageId: String,
    val issueId: String,
    val sourceStageId: String,
    val operationId: String,
    val payloadHash: String,
    val realitySupport: Boolean,
    val thinkingExpansion: Boolean,
    val objective: String,
    val expectedOutput: String,
    val confirmedAt: Long,
    val createdAt: Long,
)
