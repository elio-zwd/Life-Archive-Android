package com.elio.jianyu.viewmodel

import com.elio.jianyu.domain.*
import com.elio.jianyu.execution.SearchMode
import com.elio.jianyu.network.ApiKeySummary
import com.elio.jianyu.skill.knowledge.SkillKnowledgeSelection
import kotlinx.coroutines.flow.StateFlow

/** 会话运行的公开状态与命令；页面离开不代表结束会话或取消生成。 */
interface ConversationSession {
    val allCharacters: StateFlow<List<Character>>
    val allGroups: StateFlow<List<CharacterGroup>>
    val currentDetailSkillContent: StateFlow<String?>
    val allSessions: StateFlow<List<ChatSession>>
    val currentSessionId: StateFlow<Long?>
    val currentSession: StateFlow<ChatSession?>
    val currentParticipantIds: StateFlow<List<String>>
    val archivedSessionIds: StateFlow<Set<Long>>
    val currentMessages: StateFlow<List<Message>>
    val isRoundtableRunning: StateFlow<Boolean>
    val retryableRoundtableState: StateFlow<RetryableRoundtableState?>
    val typingCharacterIds: StateFlow<Set<String>>
    val errorMessage: StateFlow<String?>
    val isAutoNextEnabled: StateFlow<Boolean>
    val isSemanticRoutingEnabled: StateFlow<Boolean>
    val searchMode: StateFlow<SearchMode>
    val thinkingIntensity: StateFlow<String>
    val aiKeySummaries: StateFlow<List<ApiKeySummary>>
    val roundActionState: StateFlow<RoundActionState>
    val currentPlayingMessageId: StateFlow<Long?>
    val isAudioPlaying: StateFlow<Boolean>
    val allAudioMessages: StateFlow<List<Message>>

    fun dismissRetryableState()
    fun setAutoNextEnabled(enabled: Boolean)
    fun setSemanticRoutingEnabled(enabled: Boolean)
    fun setSearchMode(mode: SearchMode)
    fun setThinkingIntensity(intensity: String)
    suspend fun prepareForLocalDataDeletion()
    fun clearLocalPreferencesAfterDataDeletion(): Boolean
    suspend fun ensureFormalConversation(sessionId: Long): FormalConversationContext?
    fun clearError()
    fun ensureConversationReady()
    fun selectSession(sessionId: Long)
    fun createNewSession(title: String)
    fun deleteSession(sessionId: Long)
    fun removeSkillRoleFromCurrentSession(skillId: String)
    fun archiveSession(sessionId: Long)
    fun restoreSession(sessionId: Long)
    fun addOrUpdateCharacter(character: Character)
    fun deleteCharacter(id: String)
    fun askQuestion(text: String, targetCharacterId: String? = null): Boolean
    fun generateSessionTitle(sessionId: Long, questionRunId: Long, firstQuestion: String)
    fun renameSession(sessionId: Long, newTitle: String)
    suspend fun exportConversation(sessionId: Long): String
    suspend fun loadAvailableConversationContext(): Pair<List<Material>, List<PersonalContext>>
    fun confirmConversationContext(selections: List<ConversationContextSelection>): Boolean
    fun currentConversationContextSelections(): List<ConversationContextSelection>
    fun addSkillKnowledgeToCurrentConversation(selection: SkillKnowledgeSelection): Boolean
    fun currentActiveConversationContextSelections(): List<ConversationContextSelection>
    suspend fun saveMessageAsArtifact(messageId: Long): RepositoryResult<ConfirmedArtifactEntity>
    suspend fun saveConversationAsArtifact(sessionId: Long): RepositoryResult<ConfirmedArtifactEntity>
    suspend fun attachTextMaterial(uri: String): RepositoryResult<Material>
    fun playOrSynthesizeTts(message: Message, voiceName: String)
    fun deleteAudio(message: Message)
    fun triggerTranscode(messageId: Long, wavPath: String)
    fun triggerNextCharacterManual()
    fun letSkillRoleAnswerCurrent(skillId: String)
    fun triggerCrossDiscussion()
    fun cancelRoundtable()
    fun retryFailedCharacters()
    fun retryCharacterAnswer(questionRunId: Long, characterId: String)
    fun applyCharacterGroup(group: CharacterGroup)
    fun saveCurrentActiveAsGroup(name: String, description: String)
    fun deleteGroup(id: String)
    fun loadDetailSkill(character: Character)
    fun clearDetailSkill()
    fun updateRoundActionState(sessionId: Long)

    suspend fun createNewSessionWithSkillRole(skillId: String, title: String = "新建对话", settleTimeoutMs: Long = 5_000): Boolean
    suspend fun addSkillRoleToCurrentSessionAwait(skillId: String, settleTimeoutMs: Long = 5_000): Boolean
    suspend fun addSkillRolesToCurrentSessionAwait(skillIds: List<String>, settleTimeoutMs: Long = 5_000): Boolean
}
