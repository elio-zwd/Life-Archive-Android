package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "stages",
    foreignKeys = [
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["issueId"]),
        Index(value = ["issueId", "sequenceIndex"], unique = true),
        Index(value = ["id", "issueId"], unique = true),
    ],
)
data class StageEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val sequenceIndex: Int,
    val title: String,
    val objective: String,
    val createdAt: Long,
    val updatedAt: Long,
)
