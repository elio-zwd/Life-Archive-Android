package com.elio.jianyu.domain
import kotlinx.coroutines.flow.Flow


data class Character(
     val id: String,
    val name: String,
    val avatar: String, // Emoji 或简短标识
    val tagline: String,
    val systemPrompt: String,
    val skillAssetPath: String = "", // 技能文件在 assets 中的相对路径
    val order: Int,
    val isActive: Boolean = true,
    val skillDescriptionVector: String = "",
     val voiceConfig: String = "Aoede"
)
