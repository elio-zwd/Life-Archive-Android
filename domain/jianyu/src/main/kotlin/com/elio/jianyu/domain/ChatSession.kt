package com.elio.jianyu.domain
import kotlinx.coroutines.flow.Flow


data class ChatSession(
     val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

object MessageAnswerStatus {
    const val WAITING = "WAITING"
    const val GENERATING = "GENERATING"
    const val COMPLETED = "COMPLETED"
    const val STOPPED = "STOPPED"
    const val FAILED = "FAILED"
}


data class Message(
     val id: Long = 0,
    val chatId: Long,
    val senderId: String, // "user" or character id
    val senderName: String,
    val avatar: String, // Emoji or icon code
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPending: Boolean = false,
     val roundIndex: Int = 0,
    val audioFilePath: String? = null,
    val audioFormat: String? = null,
     val audioSizeBytes: Long = 0L,
    val issueId: String? = null,
    val stageId: String? = null,
    val executionRunId: String? = null,
    val participantSnapshotId: String? = null,
     val questionMessageId: Long? = null,
     val responseMode: String = "INDEPENDENT",
     val answerStatus: String = MessageAnswerStatus.COMPLETED,
    // null 为旧记录；[] 明确表示本次未选择参考内容，其余值按顺序保存使用快照 ID。
     val conversationContextSnapshotIds: String? = null,
)
