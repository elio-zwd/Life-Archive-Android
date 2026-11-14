package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverter

class CoreDomainConverters {
    @TypeConverter
    fun executionRunStatusToStorageValue(status: ExecutionRunStatus): String = status.storageValue

    @TypeConverter
    fun storageValueToExecutionRunStatus(value: String): ExecutionRunStatus =
        ExecutionRunStatus.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown execution run status: $value")

    @TypeConverter
    fun executionRunKindToStorageValue(kind: ExecutionRunKind): String = kind.storageValue

    @TypeConverter
    fun storageValueToExecutionRunKind(value: String): ExecutionRunKind =
        ExecutionRunKind.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown execution run kind: $value")

    @TypeConverter
    fun executionHistoryScopeToStorageValue(scope: ExecutionHistoryScope): String = scope.storageValue

    @TypeConverter
    fun storageValueToExecutionHistoryScope(value: String): ExecutionHistoryScope =
        ExecutionHistoryScope.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown execution history scope: $value")

    @TypeConverter
    fun issueThinkingPolicyToStorageValue(policy: IssueThinkingPolicy): String = policy.storageValue

    @TypeConverter
    fun storageValueToIssueThinkingPolicy(value: String): IssueThinkingPolicy =
        IssueThinkingPolicy.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown issue thinking policy: $value")

    @TypeConverter
    fun executionThinkingLevelToStorageValue(level: ExecutionThinkingLevel): String = level.storageValue

    @TypeConverter
    fun storageValueToExecutionThinkingLevel(value: String): ExecutionThinkingLevel =
        ExecutionThinkingLevel.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown execution thinking level: $value")

    @TypeConverter
    fun executionThinkingSourceToStorageValue(source: ExecutionThinkingSource): String = source.storageValue

    @TypeConverter
    fun storageValueToExecutionThinkingSource(value: String): ExecutionThinkingSource =
        ExecutionThinkingSource.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown execution thinking source: $value")
}

@Dao
abstract class CoreDomainDao {
    @Insert(entity = com.elio.jianyu.data.persistence.IssueEntity::class, onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertIssue(issue: IssueEntity)

    @Insert(entity = com.elio.jianyu.data.persistence.StageEntity::class, onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertStage(stage: StageEntity)

    @Insert(entity = com.elio.jianyu.data.persistence.ExecutionRunEntity::class, onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertExecutionRun(run: ExecutionRunEntity)

    @Insert(entity = com.elio.jianyu.data.persistence.ExecutionParticipantSnapshotEntity::class, onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertParticipantSnapshots(
        snapshots: List<ExecutionParticipantSnapshotEntity>,
    )

    @Transaction
    open suspend fun createIssueWithInitialStage(issue: IssueEntity, stage: StageEntity) {
        insertIssue(issue)
        insertStage(stage)
    }

    @Transaction
    open suspend fun createRunWithParticipants(
        run: ExecutionRunEntity,
        participants: List<ExecutionParticipantSnapshotEntity>,
    ) {
        insertExecutionRun(run)
        insertParticipantSnapshots(participants)
    }

    @Query("SELECT * FROM issues WHERE id = :issueId LIMIT 1")
    abstract suspend fun getIssue(issueId: String): IssueEntity?

    @Query("SELECT * FROM stages WHERE issueId = :issueId ORDER BY sequenceIndex ASC")
    abstract suspend fun getStagesForIssue(issueId: String): List<StageEntity>

    @Query("SELECT * FROM execution_runs WHERE id = :runId LIMIT 1")
    abstract suspend fun getExecutionRun(runId: String): ExecutionRunEntity?

    @Query(
        "SELECT * FROM execution_participant_snapshots " +
            "WHERE runId = :runId ORDER BY position ASC",
    )
    abstract suspend fun getParticipantSnapshots(runId: String): List<ExecutionParticipantSnapshotEntity>

    @Query(
        "SELECT * FROM execution_runs WHERE stageId = :stageId " +
            "AND status IN ('not_started', 'running', 'partial_success', 'retryable') " +
            "ORDER BY createdAt ASC",
    )
    abstract suspend fun getActiveRunsForStage(stageId: String): List<ExecutionRunEntity>

    @Query("SELECT * FROM messages WHERE stageId = :stageId ORDER BY timestamp ASC, id ASC")
    abstract suspend fun getMessagesForStage(stageId: String): List<Message>

    @Query(
        "UPDATE messages SET issueId = :issueId, stageId = :stageId, " +
            "executionRunId = :executionRunId, participantSnapshotId = :participantSnapshotId " +
            "WHERE id = :messageId",
    )
    abstract suspend fun bindMessageToDomain(
        messageId: Long,
        issueId: String,
        stageId: String,
        executionRunId: String?,
        participantSnapshotId: String?,
    ): Int

    @Query("DELETE FROM issues WHERE id = :issueId")
    abstract suspend fun deleteIssueById(issueId: String)
}
