package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "material_usage_snapshots",
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
            childColumns = ["runId", "issueId", "stageId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MaterialReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["materialReferenceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["id", "issueId"], unique = true),
        Index(value = ["issueId"]),
        Index(value = ["stageId", "issueId"]),
        Index(value = ["runId", "issueId", "stageId"]),
        Index(value = ["materialReferenceId"]),
        Index(value = ["runId", "materialReferenceId"], unique = true)
    ]
)
data class MaterialUsageSnapshotEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String,
    val runId: String? = null,
    val materialReferenceId: String? = null,
    val titleSnapshot: String,
    val sourceTypeSnapshot: String,
    val sourceLocatorSnapshot: String? = null,
    val contentSnapshot: String?,
    val contentHash: String,
    @ColumnInfo(defaultValue = "'available'")
    val contentState: SnapshotContentState = SnapshotContentState.AVAILABLE,
    val sourcePublishedAtSnapshot: Long? = null,
    val sourceCapturedAtSnapshot: Long? = null,
    val userConfirmedAt: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0")
    val networkAllowed: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val sensitive: Boolean = true
)
