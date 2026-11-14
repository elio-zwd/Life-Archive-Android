package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "conversation_summary_checkpoints",
    primaryKeys = ["chatId", "characterId"],
    foreignKeys = [ForeignKey(
        entity = ChatSession::class,
        parentColumns = ["id"],
        childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ConversationSummaryCheckpoint(
    val chatId: Long,
    val characterId: String,
    val summary: String,
    val coveredThroughMessageId: Long,
    val sourceFingerprint: String,
    val promptVersion: Int,
    val updatedAt: Long,
)
