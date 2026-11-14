package com.elio.jianyu.audio.assets

import com.elio.jianyu.domain.AudioFileState

enum class AudioGenerationErrorCode {
    SOURCE_NOT_FOUND,
    PENDING_MESSAGE,
    DRAFT_SOURCE,
    CROSS_ISSUE,
    CROSS_STAGE,
    UNCONFIRMED_ARTIFACT,
    SOURCE_CHANGED,
    UNSUPPORTED_CONFIG,
    INSUFFICIENT_STORAGE,
    PENDING_WITHOUT_ACTIVE_WORK,
    DUPLICATE_CONFLICT,
    SCHEDULE_FAILED,
    AUTH_UNAVAILABLE,
    RATE_LIMITED,
    OFFLINE,
    TIMEOUT,
    EMPTY_RESPONSE,
    INVALID_RESPONSE,
    INVALID_AUDIO_FILE,
    FILE_IO,
    REPOSITORY_REJECTED,
    ASSET_NOT_FOUND,
    INVALID_STATE,
    CANCELED,
    DELETED,
    PURGE_REQUESTED,
    UNKNOWN,
}

sealed interface AudioSourceReference {
    val issueId: String
    val stageId: String

    data class Message(
        override val issueId: String,
        override val stageId: String,
        val messageId: Long,
    ) : AudioSourceReference

    data class Artifact(
        override val issueId: String,
        override val stageId: String,
        val artifactId: String,
    ) : AudioSourceReference
}

data class AudioSourceSnapshot(
    val source: AudioAssetSource,
    val content: String,
)

sealed interface AudioSourceLoadResult {
    data class Ready(val snapshot: AudioSourceSnapshot) : AudioSourceLoadResult
    data class Rejected(val errorCode: AudioGenerationErrorCode) : AudioSourceLoadResult
}

data class AudioAssetRecord(
    val id: String,
    val source: AudioAssetSource,
    val config: AudioGenerationConfig,
    val generationKey: String,
    val fileState: AudioFileState,
    val storagePath: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val deletedAt: Long?,
    val purgeRequestedAt: Long?,
)

data class CreatePendingAudioCommand(
    val audioAssetId: String,
    val source: AudioAssetSource,
    val config: AudioGenerationConfig,
    val generationKey: String,
)

sealed interface AudioAssetCreateResult {
    data class Created(val asset: AudioAssetRecord) : AudioAssetCreateResult
    data class Existing(val asset: AudioAssetRecord) : AudioAssetCreateResult
    data object Conflict : AudioAssetCreateResult
}

data class ResetAudioForRetryCommand(
    val audioAssetId: String,
    val expectedState: AudioFileState,
    val source: AudioAssetSource,
    val config: AudioGenerationConfig,
    val generationKey: String,
)

sealed interface AudioAssetRetryResetResult {
    data class Reset(val asset: AudioAssetRecord) : AudioAssetRetryResetResult
    data object Rejected : AudioAssetRetryResetResult
    data object Conflict : AudioAssetRetryResetResult
}

data class MarkAudioAvailableCommand(
    val audioAssetId: String,
    val relativePath: String,
    val mimeType: String,
    val format: AudioTargetFormat,
    val sizeBytes: Long,
    val expectedState: AudioFileState = AudioFileState.PENDING,
)

interface AudioAssetRepositoryPort {
    suspend fun loadSource(reference: AudioSourceReference): AudioSourceLoadResult
    suspend fun findByGenerationKey(generationKey: String): AudioAssetRecord?
    suspend fun createPending(command: CreatePendingAudioCommand): AudioAssetCreateResult
    suspend fun loadAsset(audioAssetId: String): AudioAssetRecord?
    suspend fun markAvailable(command: MarkAudioAvailableCommand): Boolean
    suspend fun markFailed(audioAssetId: String, expectedState: AudioFileState): Boolean
    suspend fun markCanceled(audioAssetId: String, expectedState: AudioFileState): Boolean

    suspend fun resetForRetry(
        command: ResetAudioForRetryCommand,
    ): AudioAssetRetryResetResult = AudioAssetRetryResetResult.Rejected
}

interface AudioGenerationSchedulerPort {
    suspend fun isActive(uniqueWorkName: String): Boolean
    suspend fun schedule(plan: AudioGenerationWorkPlan): Boolean
    suspend fun cancel(uniqueWorkName: String): Boolean
}

data class CreateAudioGenerationCommand(
    val sourceReference: AudioSourceReference,
    val config: AudioGenerationConfig,
    val userConfirmed: Boolean,
    val estimatedOutputBytes: Long,
    val minimumReservationBytes: Long,
    val safetyMarginBytes: Long,
)

sealed interface AudioGenerationRequestResult {
    data object ConfirmationRequired : AudioGenerationRequestResult
    data class Queued(
        val asset: AudioAssetRecord,
        val workPlan: AudioGenerationWorkPlan,
    ) : AudioGenerationRequestResult
    data class ReusedAvailable(val asset: AudioAssetRecord) : AudioGenerationRequestResult
    data class ReusedPending(val asset: AudioAssetRecord) : AudioGenerationRequestResult
    data class ExplicitRetryRequired(val asset: AudioAssetRecord) : AudioGenerationRequestResult
    data class Failure(val errorCode: AudioGenerationErrorCode) : AudioGenerationRequestResult
}

data class RetryAudioGenerationCommand(
    val audioAssetId: String,
    val userConfirmed: Boolean,
    val estimatedOutputBytes: Long,
    val minimumReservationBytes: Long,
    val safetyMarginBytes: Long,
)

sealed interface AudioGenerationRetryResult {
    data object ConfirmationRequired : AudioGenerationRetryResult
    data class Queued(
        val asset: AudioAssetRecord,
        val workPlan: AudioGenerationWorkPlan,
    ) : AudioGenerationRetryResult
    data class ReusedAvailable(val asset: AudioAssetRecord) : AudioGenerationRetryResult
    data class ReusedPending(val asset: AudioAssetRecord) : AudioGenerationRetryResult
    data class Failure(val errorCode: AudioGenerationErrorCode) : AudioGenerationRetryResult
}

sealed interface AudioGenerationExecutionResult {
    data class Available(
        val audioAssetId: String,
        val relativePath: String,
    ) : AudioGenerationExecutionResult
    data class Suppressed(val errorCode: AudioGenerationErrorCode) : AudioGenerationExecutionResult
    data class Failure(val errorCode: AudioGenerationErrorCode) : AudioGenerationExecutionResult
}

sealed interface AudioGenerationCancelResult {
    data class Canceled(val audioAssetId: String) : AudioGenerationCancelResult
    data class Failure(val errorCode: AudioGenerationErrorCode) : AudioGenerationCancelResult
}
