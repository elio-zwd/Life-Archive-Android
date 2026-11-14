package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "issue_relations",
    foreignKeys = [
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceIssueId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetIssueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = IssueArchiveEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceArchiveEventId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["sourceIssueId"]),
        Index(value = ["targetIssueId"]),
        Index(value = ["sourceArchiveEventId"]),
        Index(value = ["operationId"], unique = true),
        Index(value = ["sourceIssueId", "targetIssueId", "relationType"]),
    ],
)
data class IssueRelationEntity(
    @PrimaryKey val id: String,
    val sourceIssueId: String?,
    val targetIssueId: String,
    val sourceArchiveEventId: String?,
    val operationId: String,
    val payloadHash: String,
    @ColumnInfo(defaultValue = "'continuation'")
    val relationType: IssueRelationType = IssueRelationType.CONTINUATION,
    val createdAt: Long,
    val sourcePurgedAt: Long? = null,
)
