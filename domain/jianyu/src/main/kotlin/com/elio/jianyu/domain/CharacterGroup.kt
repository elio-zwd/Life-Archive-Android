package com.elio.jianyu.domain
import kotlinx.coroutines.flow.Flow


data class CharacterGroup(
     val id: String,
    val name: String,
    val description: String,
    val characterIds: String, // 逗号分隔的 id，例如 "elon_musk,naval_ravikant"
    val isPreset: Boolean = false
)
