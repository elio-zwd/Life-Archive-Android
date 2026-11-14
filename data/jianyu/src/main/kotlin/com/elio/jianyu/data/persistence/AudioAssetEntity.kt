package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "audio_assets",
    foreignKeys = [
        ForeignKey(
            entity = StageEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["stageId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = Message::class,
            parentColumns = ["id", "issueId", "stageId"],
            childColumns = ["sourceMessageId", "issueId", "stageId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ConfirmedArtifactEntity::class,
            parentColumns = ["id", "issueId", "stageId"],
            childColumns = ["sourceArtifactId", "issueId", "stageId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["stageId", "issueId"]),
        Index(value = ["sourceMessageId", "issueId", "stageId"]),
        Index(value = ["sourceArtifactId", "issueId", "stageId"]),
        Index(value = ["storagePath"], unique = true),
        Index(value = ["generationKey"], unique = true),
        Index(value = ["deletedAt"])
    ]
)
data class AudioAssetEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String,
    val sourceMessageId: Long? = null,
    val sourceArtifactId: String? = null,
    val storagePath: String,
    val mimeType: String,
    val format: String,
    val sizeBytes: Long,
    @ColumnInfo(defaultValue = "'pending'")
    val fileState: AudioFileState = AudioFileState.PENDING,
    val generationKey: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val purgeRequestedAt: Long? = null
)
