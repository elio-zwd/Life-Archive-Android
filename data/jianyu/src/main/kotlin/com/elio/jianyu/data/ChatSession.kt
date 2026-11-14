package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import com.elio.jianyu.storage.ownedAudioFile
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.elio.jianyu.telemetry.InteractionChainStore
import com.elio.jianyu.telemetry.PrivacySafeLogger
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("UPDATE messages SET isPending = 0, answerStatus = 'STOPPED' WHERE isPending = 1")
    suspend fun markPendingInterrupted()
    @Query("SELECT * FROM conversation_summary_checkpoints WHERE chatId = :chatId AND characterId = :characterId")
    suspend fun getConversationSummary(chatId: Long, characterId: String): ConversationSummaryCheckpoint?

    @Insert(entity = com.elio.jianyu.data.persistence.ConversationSummaryCheckpoint::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConversationSummary(checkpoint: ConversationSummaryCheckpoint)

    @Query(
        "SELECT * FROM chat_sessions WHERE id NOT IN (" +
            "SELECT legacyChatSessionId FROM issues " +
            "WHERE legacyChatSessionId IS NOT NULL AND id NOT LIKE 'legacy-chat-%' " +
            "AND id NOT LIKE 'dialog-session-%'" +
            ") ORDER BY createdAt DESC"
    )
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): ChatSession?

    @Insert(entity = com.elio.jianyu.data.persistence.ChatSession::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession): Long

    @Query(
        "SELECT EXISTS(SELECT 1 FROM issues " +
            "WHERE legacyChatSessionId = :sessionId AND id NOT LIKE 'legacy-chat-%')"
    )
    suspend fun isDomainCompatibilitySession(sessionId: Long): Boolean

    @Query(
        "DELETE FROM chat_sessions WHERE id = :id AND NOT EXISTS (" +
            "SELECT 1 FROM issues " +
            "WHERE legacyChatSessionId = :id AND id NOT LIKE 'legacy-chat-%'" +
            ")"
    )
    suspend fun deleteSessionById(id: Long)

    @Query(
        "UPDATE chat_sessions SET title = :title WHERE id = :id AND NOT EXISTS (" +
            "SELECT 1 FROM issues " +
            "WHERE legacyChatSessionId = :id AND id NOT LIKE 'legacy-chat-%' " +
            "AND id NOT LIKE 'dialog-session-%'" +
            ")"
    )
    suspend fun updateSessionTitle(id: Long, title: String)

    @Query(
        "DELETE FROM messages WHERE chatId = :chatId AND NOT EXISTS (" +
            "SELECT 1 FROM issues " +
            "WHERE legacyChatSessionId = :chatId AND id NOT LIKE 'legacy-chat-%'" +
            ")"
    )
    suspend fun deleteMessagesByChatId(chatId: Long)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC, id ASC")
    fun getMessagesForChatFlow(chatId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesForChat(chatId: Long): List<Message>

    @Query(
        "SELECT id, 'material' AS sourceType, materialReferenceId AS sourceId, " +
            "titleSnapshot AS title, sourceTypeSnapshot AS sourceKind, sourceLocatorSnapshot AS sourceLocator, " +
            "contentSnapshot AS content, contentHash, contentState, userConfirmedAt AS confirmedAt, " +
            "networkAllowed, sensitive FROM material_usage_snapshots " +
            "WHERE issueId = :issueId AND stageId = :stageId AND runId IS NULL AND id LIKE :questionPrefix || '%' " +
            "UNION ALL " +
            "SELECT id, 'personal_context', personalContextEntryId, titleSnapshot, 'personal_context', NULL, " +
            "contentSnapshot, contentHash, contentState, userConfirmedAt, networkAllowed, sensitive " +
            "FROM personal_context_usage_snapshots " +
            "WHERE issueId = :issueId AND stageId = :stageId AND runId IS NULL AND id LIKE :questionPrefix || '%' " +
            "UNION ALL " +
            "SELECT id, 'skill_knowledge', documentId, titleSnapshot, sourceSkillId, relativePathSnapshot, " +
            "contentSnapshot, contentHash, 'available', userConfirmedAt, 1, 0 " +
            "FROM skill_knowledge_usage_snapshots " +
            "WHERE issueId = :issueId AND stageId = :stageId AND runId IS NULL AND id LIKE :questionPrefix || '%'"
    )
    suspend fun getConversationContextSnapshots(
        issueId: String,
        stageId: String,
        questionPrefix: String,
    ): List<ConversationContextSnapshotRow>

    /** 将首页旧聊天消息补齐到对应的正式议题和对话节点。 */
    @Query(
        "UPDATE messages SET issueId = :issueId, stageId = :stageId " +
            "WHERE chatId = :chatId AND issueId IS NULL AND stageId IS NULL"
    )
    suspend fun backfillDomainContext(chatId: Long, issueId: String, stageId: String): Int

    @Insert(entity = com.elio.jianyu.data.persistence.Message::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    @Query(
        "UPDATE messages SET text = :text, answerStatus = 'GENERATING' WHERE id = :id AND isPending = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM issues " +
            "WHERE legacyChatSessionId = messages.chatId " +
            "AND issues.id NOT LIKE 'legacy-chat-%' " +
            "AND issues.id NOT LIKE 'dialog-session-%')"
    )
    suspend fun updatePendingMessageText(id: Long, text: String)

    @Query(
        "UPDATE messages SET text = :text, isPending = 0, answerStatus = :answerStatus " +
            "WHERE id = :id AND isPending = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM issues " +
            "WHERE legacyChatSessionId = messages.chatId " +
            "AND issues.id NOT LIKE 'legacy-chat-%' " +
            "AND issues.id NOT LIKE 'dialog-session-%')"
    )
    suspend fun completePendingMessage(id: Long, text: String, answerStatus: String)

    @Query(
        "DELETE FROM messages WHERE id = :id AND NOT EXISTS (" +
            "SELECT 1 FROM issues WHERE legacyChatSessionId = messages.chatId " +
            "AND issues.id NOT LIKE 'legacy-chat-%' " +
            "AND issues.id NOT LIKE 'dialog-session-%')"
    )
    suspend fun deleteMessageById(id: Long)

    @Query(
        "DELETE FROM messages WHERE chatId = :chatId AND isPending = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM issues " +
            "WHERE legacyChatSessionId = messages.chatId " +
            "AND issues.id NOT LIKE 'legacy-chat-%' " +
            "AND issues.id NOT LIKE 'dialog-session-%')"
    )
    suspend fun removePendingMessages(chatId: Long)

    @Query(
        "DELETE FROM messages WHERE isPending = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM issues " +
            "WHERE legacyChatSessionId = messages.chatId " +
            "AND issues.id NOT LIKE 'legacy-chat-%' " +
            "AND issues.id NOT LIKE 'dialog-session-%')"
    )
    suspend fun removeAllPendingMessages()

    @Query(
        "UPDATE messages SET audioFilePath = :path, audioFormat = :format, " +
            "audioSizeBytes = :size WHERE id = :id AND NOT EXISTS (" +
            "SELECT 1 FROM issues WHERE legacyChatSessionId = messages.chatId " +
            "AND issues.id NOT LIKE 'legacy-chat-%' " +
            "AND issues.id NOT LIKE 'dialog-session-%')"
    )
    suspend fun updateMessageAudio(id: Long, path: String?, format: String?, size: Long)

    @Query("SELECT * FROM messages WHERE audioFilePath IS NOT NULL AND audioFilePath != '' ORDER BY timestamp DESC")
    fun getAudioMessagesFlow(): Flow<List<Message>>
}

class ChatRepository(private val chatDao: ChatDao, context: android.content.Context) {
    private val context = context.applicationContext
    suspend fun markPendingInterrupted() = chatDao.markPendingInterrupted()
    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessions()
    val audioMessages: Flow<List<Message>> = chatDao.getAudioMessagesFlow()

    fun getMessagesFlow(chatId: Long): Flow<List<Message>> = chatDao.getMessagesForChatFlow(chatId)

    suspend fun getMessages(chatId: Long): List<Message> = chatDao.getMessagesForChat(chatId)

    suspend fun backfillDomainContext(chatId: Long, issueId: String, stageId: String): Int =
        chatDao.backfillDomainContext(chatId, issueId, stageId)

    suspend fun getSessionById(id: Long): ChatSession? = chatDao.getSessionById(id)

    suspend fun createSession(title: String): Long {
        return chatDao.insertSession(ChatSession(title = title))
    }

    suspend fun deleteSession(id: Long) {
        if (chatDao.isDomainCompatibilitySession(id)) {
            PrivacySafeLogger.w(
                "ChatRepository",
                "Skipped deletion of domain compatibility session"
            )
            return
        }
        InteractionChainStore.clearSession(id)
        try {
            val messages = chatDao.getMessagesForChat(id)
            messages.forEach { message ->
                val path = message.audioFilePath
                if (!path.isNullOrBlank()) {
                    context.ownedAudioFile(path)?.delete()
                }
            }
        } catch (error: Exception) {
            PrivacySafeLogger.e("ChatRepository", "Session audio cleanup failed", error)
        }
        chatDao.deleteSessionById(id)
        chatDao.deleteMessagesByChatId(id)
    }

    suspend fun updateSessionTitle(id: Long, title: String) {
        chatDao.updateSessionTitle(id, title)
    }

    suspend fun updateMessageAudio(id: Long, path: String?, format: String?, size: Long) {
        chatDao.updateMessageAudio(id, path, format, size)
    }

    suspend fun insertMessage(message: Message): Long = chatDao.insertMessage(message)

    suspend fun updatePendingMessageText(id: Long, text: String) {
        chatDao.updatePendingMessageText(id, text)
    }

    suspend fun completePendingMessage(
        id: Long,
        text: String,
        answerStatus: String = MessageAnswerStatus.COMPLETED,
    ) {
        chatDao.completePendingMessage(id, text, answerStatus)
    }

    suspend fun deleteMessageById(id: Long) = chatDao.deleteMessageById(id)

    suspend fun removePendingMessages(chatId: Long) = chatDao.removePendingMessages(chatId)

    suspend fun removeAllPendingMessages() = chatDao.removeAllPendingMessages()
}
