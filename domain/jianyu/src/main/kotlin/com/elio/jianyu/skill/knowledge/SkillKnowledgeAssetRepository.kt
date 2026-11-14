package com.elio.jianyu.skill.knowledge



interface SkillKnowledgeAssetReader {
    fun readBytes(path: String): ByteArray
}

interface SkillKnowledgeRepository {
    fun listSkills(): List<SkillKnowledgeSkill>
    fun listDocuments(skillId: String): List<SkillKnowledgeDocument>
    fun loadDocumentContent(skillId: String, documentId: String): String
    fun loadChunkContent(
        skillId: String,
        document: SkillKnowledgeDocument,
        chunk: SkillKnowledgeChunk,
    ): String
    fun loadVector(chunk: SkillKnowledgeChunk): FloatArray
}
