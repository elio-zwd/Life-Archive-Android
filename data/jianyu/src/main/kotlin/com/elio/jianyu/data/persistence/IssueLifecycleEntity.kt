package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "issue_lifecycle",
    foreignKeys = [
        ForeignKey(
            entity = IssueEntity::class,
            parentColumns = ["id"],
            childColumns = ["issueId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["state"]),
        Index(value = ["purgeRequestedAt"])
    ]
)
data class IssueLifecycleEntity(
    @PrimaryKey val issueId: String,
    @ColumnInfo(defaultValue = "'active'")
    val state: IssueLifecycleState = IssueLifecycleState.ACTIVE,
    val previousState: IssueLifecycleState? = null,
    val stateChangedAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null,
    val trashedAt: Long? = null,
    val purgeRequestedAt: Long? = null
)
