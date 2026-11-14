package com.elio.jianyu.audio.assets

import com.elio.jianyu.domain.AudioFileState

interface AudioAssetLifecycleRepositoryPort {
    suspend fun getAudioAsset(audioAssetId: String): AudioAssetRecord?

    suspend fun listAudioAssetsForIssue(issueId: String): List<AudioAssetRecord>

    suspend fun listAudioAssetsForStage(
        issueId: String,
        stageId: String,
    ): List<AudioAssetRecord>

    suspend fun markMissing(
        audioAssetId: String,
        expectedState: AudioFileState,
    ): Boolean

    suspend fun requestDelete(
        command: PersistAudioDeleteRequestCommand,
    ): AudioDeleteWriteResult
}

data class PersistAudioDeleteRequestCommand(
    val audioAssetId: String,
    val expectedState: AudioFileState,
    val requestedAt: Long,
)

sealed interface AudioDeleteWriteResult {
    data class Requested(
        val asset: AudioAssetRecord,
    ) : AudioDeleteWriteResult

    data class AlreadyRequested(
        val asset: AudioAssetRecord,
    ) : AudioDeleteWriteResult

    data object Rejected : AudioDeleteWriteResult
}

data class AudioFileReconciliationResult(
    val markedMissingAssetIds: List<String>,
    val staleAssetIds: List<String>,
    val orphanReport: AudioOrphanReport,
)

data class AudioPurgeImpact(
    val issueId: String,
    val assetCount: Int,
    val pendingAssetCount: Int,
    val referencedFileCount: Int,
    val referencedFileBytes: Long,
    val missingAssetIds: List<String>,
    val uniqueWorkNames: List<String>,
    val orphanReport: AudioOrphanReport,
)

data class RequestAudioAssetDeleteCommand(
    val audioAssetId: String,
    val requestedAt: Long,
    val userConfirmed: Boolean,
)

sealed interface AudioAssetDeleteRequestResult {
    data object ConfirmationRequired : AudioAssetDeleteRequestResult

    data class Requested(
        val audioAssetId: String,
    ) : AudioAssetDeleteRequestResult

    data class AlreadyRequested(
        val audioAssetId: String,
    ) : AudioAssetDeleteRequestResult

    data class Failure(
        val errorCode: AudioGenerationErrorCode,
    ) : AudioAssetDeleteRequestResult
}

/**
 * 正式音频资产的缺失对账、影响检查和受控删除请求入口。
 *
 * 该服务没有物理删除 API：孤儿文件、正式文件与来源对象只能由 PR09-12 在用户确认后清理。
 * 孤儿扫描必须使用全局引用视图，不能把其他议题仍在引用的文件误报为孤儿。
 */
