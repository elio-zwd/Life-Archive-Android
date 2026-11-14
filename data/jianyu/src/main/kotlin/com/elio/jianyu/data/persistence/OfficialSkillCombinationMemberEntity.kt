package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "official_skill_combination_members",
    primaryKeys = ["combinationId", "officialSkillId"],
    foreignKeys = [
        ForeignKey(
            entity = OfficialSkillCombinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["combinationId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["combinationId"]),
        Index(value = ["combinationId", "position"], unique = true)
    ]
)
data class OfficialSkillCombinationMemberEntity(
    val combinationId: String,
    val officialSkillId: String,
    val position: Int,
    val defaultResponsibility: String? = null,
    val createdAt: Long
)
