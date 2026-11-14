package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "stage_advancement_skill_members",
    primaryKeys = ["stageId", "officialSkillId"],
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
        Index(value = ["sourceRunId"]),
        Index(value = ["sourceParticipantSnapshotId"]),
    ],
)
data class StageAdvancementSkillMemberEntity(
    val stageId: String,
    val issueId: String,
    val officialSkillId: String,
    val position: Int,
    val responsibility: String,
    val sourceRunId: String? = null,
    val sourceParticipantSnapshotId: String? = null,
    val catalogVersionBasis: String? = null,
    val confirmedAt: Long,
)
