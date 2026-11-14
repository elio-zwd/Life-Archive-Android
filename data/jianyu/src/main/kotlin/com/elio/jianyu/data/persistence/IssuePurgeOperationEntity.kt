package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "issue_purge_operations",
    foreignKeys = [
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["issueId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["issueId"], unique = true),
        Index(value = ["operationId"], unique = true),
        Index(value = ["state"]),
        Index(value = ["updatedAt"]),
    ],
)
data class IssuePurgeOperationEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val operationId: String,
    val payloadHash: String,
    val impactHash: String?,
    @ColumnInfo(defaultValue = "'requested'")
    val state: IssuePurgeState = IssuePurgeState.REQUESTED,
    val requestedAt: Long,
    val startedAt: Long? = null,
    val updatedAt: Long,
    val failedAt: Long? = null,
    val failureCode: String? = null,
    val failurePhase: IssuePurgeFailurePhase? = null,
    @ColumnInfo(defaultValue = "0")
    val retryCount: Int = 0,
)
