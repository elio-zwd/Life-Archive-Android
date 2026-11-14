package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(tableName = "characters")
data class Character(
    @PrimaryKey val id: String,
    val name: String,
    val avatar: String, // Emoji 或简短标识
    val tagline: String,
    val systemPrompt: String,
    val skillAssetPath: String = "", // 技能文件在 assets 中的相对路径
    val order: Int,
    val isActive: Boolean = true,
    val skillDescriptionVector: String = "",
    @ColumnInfo(defaultValue = "Aoede") val voiceConfig: String = "Aoede"
)
