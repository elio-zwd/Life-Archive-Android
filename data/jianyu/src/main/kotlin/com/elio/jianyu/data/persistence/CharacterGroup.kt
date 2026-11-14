package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(tableName = "character_groups")
data class CharacterGroup(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val characterIds: String, // 逗号分隔的 id，例如 "elon_musk,naval_ravikant"
    val isPreset: Boolean = false
)
