package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "official_skill_combinations",
    indices = [
        Index(value = ["name"]),
        Index(value = ["deletedAt"])
    ]
)
data class OfficialSkillCombinationEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "1")
    val isEnabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)
