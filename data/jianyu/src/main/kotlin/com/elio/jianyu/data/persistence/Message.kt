package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id", "issueId", "stageId"],
            childColumns = ["executionRunId", "issueId", "stageId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ExecutionParticipantSnapshotEntity::class,
            parentColumns = ["id", "runId"],
            childColumns = ["participantSnapshotId", "executionRunId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["id", "issueId"], unique = true),
        Index(value = ["id", "issueId", "stageId"], unique = true),
        Index(value = ["issueId"]),
        Index(value = ["stageId", "issueId"]),
        Index(value = ["executionRunId", "issueId", "stageId"]),
        Index(value = ["participantSnapshotId", "executionRunId"])
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val senderId: String, // "user" or character id
    val senderName: String,
    val avatar: String, // Emoji or icon code
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPending: Boolean = false,
    @ColumnInfo(defaultValue = "0") val roundIndex: Int = 0,
    val audioFilePath: String? = null,
    val audioFormat: String? = null,
    @ColumnInfo(defaultValue = "0") val audioSizeBytes: Long = 0L,
    val issueId: String? = null,
    val stageId: String? = null,
    val executionRunId: String? = null,
    val participantSnapshotId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val questionMessageId: Long? = null,
    @ColumnInfo(defaultValue = "'INDEPENDENT'") val responseMode: String = "INDEPENDENT",
    @ColumnInfo(defaultValue = "'COMPLETED'") val answerStatus: String = MessageAnswerStatus.COMPLETED,
    // null 为旧记录；[] 明确表示本次未选择参考内容，其余值按顺序保存使用快照 ID。
    @ColumnInfo(defaultValue = "NULL") val conversationContextSnapshotIds: String? = null,
)
