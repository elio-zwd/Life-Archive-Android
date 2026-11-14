package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "personal_context_entries",
    indices = [
        Index(value = ["contentHash"]),
        Index(value = ["lifecycleState"]),
        Index(value = ["deletedAt"])
    ]
)
data class PersonalContextEntryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val contentHash: String,
    @ColumnInfo(defaultValue = "1")
    val isEnabled: Boolean = true,
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
