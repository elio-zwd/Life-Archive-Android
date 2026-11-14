package com.elio.jianyu.skill.knowledge

import kotlinx.serialization.Serializable

@Serializable
internal data class SkillKnowledgeManifest(
    val schemaVersion: Int,
    val model: String,
    val vectorDimension: Int,
    val vectorEncoding: String,
    val skills: List<SkillKnowledgeSkill>,
)
