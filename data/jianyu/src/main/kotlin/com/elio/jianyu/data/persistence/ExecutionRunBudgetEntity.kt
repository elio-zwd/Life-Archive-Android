package com.elio.jianyu.data.persistence

import androidx.room.*
import com.elio.jianyu.domain.*

@Entity(
    tableName = "execution_run_budgets",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootRunId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class ExecutionRunBudgetEntity(
    @PrimaryKey val rootRunId: String,
    @ColumnInfo(defaultValue = "0")
    val usedApiCalls: Int = 0,
    val maxCharacters: Int,
    val maxSearchQueriesPerCharacter: Int,
    val maxOutputTokensPerAnswer: Int,
    @ColumnInfo(defaultValue = "0")
    val closed: Boolean = false,
    val updatedAt: Long,
)
