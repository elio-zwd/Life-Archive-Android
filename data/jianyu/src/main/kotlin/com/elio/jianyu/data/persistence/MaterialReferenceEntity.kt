package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "material_references",
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
        )
    ],
    indices = [
        Index(value = ["id", "issueId"], unique = true),
        Index(value = ["issueId"]),
        Index(value = ["stageId", "issueId"]),
        Index(value = ["sourceLocator"]),
        Index(value = ["lifecycleState"]),
        Index(value = ["deletedAt"])
    ]
)
data class MaterialReferenceEntity(
    @PrimaryKey val id: String,
    val issueId: String,
    val stageId: String? = null,
    val title: String,
    val sourceType: String,
    val sourceLocator: String? = null,
    val content: String,
    val contentHash: String,
    val sourcePublishedAt: Long? = null,
    val sourceCapturedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'active'")
    val lifecycleState: ContextSourceLifecycle = ContextSourceLifecycle.ACTIVE,
    @ColumnInfo(defaultValue = "0")
    val sensitive: Boolean = false,
    val disabledAt: Long? = null,
    val archivedAt: Long? = null,
    val deletedAt: Long? = null,
    val purgeRequestedAt: Long? = null,
    val purgedAt: Long? = null
)
