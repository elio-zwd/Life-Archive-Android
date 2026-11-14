package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "artifact_message_sources",
    primaryKeys = ["artifactId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = ConfirmedArtifactEntity::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["artifactId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = Message::class,
            parentColumns = ["id", "issueId"],
            childColumns = ["messageId", "issueId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["artifactId", "issueId"]),
        Index(value = ["messageId", "issueId"])
    ]
)
data class ArtifactMessageSourceEntity(
    val artifactId: String,
    val issueId: String,
    val messageId: Long,
    val createdAt: Long
)
