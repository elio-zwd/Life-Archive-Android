package com.elio.lifearchive.domain.pet

import kotlinx.coroutines.flow.StateFlow

data class CompanionProfile(
    val name: String = "伙伴",
    val personality: String = "温和、自然、有主见。认真倾听，记得共同经历，尊重用户的选择，不用奖惩或内疚感催促用户。",
    val draft: String = "",
    val showInApp: Boolean = false,
)
enum class MessageRole { USER, COMPANION }
enum class ReplyStatus { COMPLETE, GENERATING, CANCELLED, FAILED, INTERRUPTED }
enum class CompanionEmotion { CALM, HAPPY, THINKING, CARING }
data class CompanionMessage(
    val id: String,
    val operationId: String,
    val role: MessageRole,
    val text: String,
    val status: ReplyStatus,
    val createdAt: Long,
    val emotion: CompanionEmotion = CompanionEmotion.CALM,
    val sourceMessageId: String? = null,
    val error: String? = null,
)
data class CompanionMemory(val id: String, val text: String, val sourceMessageId: String?, val revision: Long)
data class CompanionState(
    val ready: Boolean = false,
    val profile: CompanionProfile = CompanionProfile(),
    val messages: List<CompanionMessage> = emptyList(),
    val memories: List<CompanionMemory> = emptyList(),
) {
    val generating: Boolean get() = messages.any { it.status == ReplyStatus.GENERATING }
}

interface CompanionRepository {
    val state: StateFlow<CompanionState>
    suspend fun initialize()
    suspend fun updateProfile(name: String, personality: String)
    suspend fun saveDraft(text: String)
    suspend fun setInAppVisible(visible: Boolean)
    suspend fun send(text: String, operationId: String): Boolean
    suspend fun retry(messageId: String): Boolean
    suspend fun cancel()
    suspend fun remember(text: String, sourceMessageId: String? = null)
    suspend fun editMemory(id: String, revision: Long, text: String): Boolean
    suspend fun deleteMemory(id: String, revision: Long): Boolean
}
