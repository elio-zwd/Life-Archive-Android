package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "issues",
    indices = [Index(value = ["legacyChatSessionId"], unique = true)],
)
data class IssueEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val legacyChatSessionId: Long? = null,
    @ColumnInfo(defaultValue = "'auto'")
    val defaultThinkingPolicy: IssueThinkingPolicy = IssueThinkingPolicy.AUTO,
)
