package com.elio.jianyu.audio.workspace
import com.elio.jianyu.audio.assets.AudioAssetDeleteRequestResult
import com.elio.jianyu.audio.assets.AudioAssetPlaybackResult
import com.elio.jianyu.audio.assets.AudioAssetPlaybackState
import com.elio.jianyu.audio.assets.AudioAssetRecord
import com.elio.jianyu.audio.assets.AudioFileReconciliationResult
import com.elio.jianyu.audio.assets.AudioGenerationCancelResult
import com.elio.jianyu.audio.assets.AudioGenerationRequestResult
import com.elio.jianyu.audio.assets.AudioGenerationRetryResult
import com.elio.jianyu.audio.assets.AudioSourceReference
import com.elio.jianyu.audio.assets.CreateAudioGenerationCommand
import com.elio.jianyu.audio.assets.RequestAudioAssetDeleteCommand
import com.elio.jianyu.audio.assets.RetryAudioGenerationCommand
import com.elio.jianyu.audio.runtime.JianyuAudioRuntime
import com.elio.jianyu.domain.AudioFileState
import com.elio.jianyu.domain.FormalAudioV1Policy

class RuntimeAudioAssetWorkspaceOperations(
    private val runtime: JianyuAudioRuntime,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) : AudioAssetWorkspaceOperations {
    override suspend fun listStage(issueId: String, stageId: String): List<AudioAssetRecord> =
        runtime.lifecycleService.listAudioAssetsForStage(issueId, stageId)

    override suspend fun reconcile(issueId: String): AudioFileReconciliationResult =
        runtime.lifecycleService.reconcileFilesForIssue(issueId)

    override suspend fun generate(reference: AudioSourceReference): AudioAssetWorkspaceOperationResult {
        val result = runtime.generationCoordinator.createGenerationRequest(
            CreateAudioGenerationCommand(
                sourceReference = reference,
                config = FormalAudioV1Policy.defaultConfig,
                userConfirmed = true,
                estimatedOutputBytes = ESTIMATED_OUTPUT_BYTES,
                minimumReservationBytes = MINIMUM_RESERVATION_BYTES,
                safetyMarginBytes = SAFETY_MARGIN_BYTES,
            ),
        )
        return when (result) {
            is AudioGenerationRequestResult.Queued -> success("音频已加入后台生成队列")
            is AudioGenerationRequestResult.ReusedAvailable -> success("已复用可播放音频")
            is AudioGenerationRequestResult.ReusedPending -> success("相同音频正在生成")
            is AudioGenerationRequestResult.ExplicitRetryRequired -> failure("EXPLICIT_RETRY_REQUIRED")
            is AudioGenerationRequestResult.Failure -> failure(result.errorCode.name)
            AudioGenerationRequestResult.ConfirmationRequired -> failure("CONFIRMATION_REQUIRED")
        }
    }

    override suspend fun retry(audioAssetId: String): AudioAssetWorkspaceOperationResult {
        val result = runtime.generationCoordinator.retryGeneration(
            RetryAudioGenerationCommand(
                audioAssetId = audioAssetId,
                userConfirmed = true,
                estimatedOutputBytes = ESTIMATED_OUTPUT_BYTES,
                minimumReservationBytes = MINIMUM_RESERVATION_BYTES,
                safetyMarginBytes = SAFETY_MARGIN_BYTES,
            ),
        )
        return when (result) {
            is AudioGenerationRetryResult.Queued -> success("音频已重新加入后台队列")
            is AudioGenerationRetryResult.ReusedAvailable -> success("音频已可播放")
            is AudioGenerationRetryResult.ReusedPending -> success("音频仍在生成")
            is AudioGenerationRetryResult.Failure -> failure(result.errorCode.name)
            AudioGenerationRetryResult.ConfirmationRequired -> failure("CONFIRMATION_REQUIRED")
        }
    }

    override suspend fun cancel(audioAssetId: String): AudioAssetWorkspaceOperationResult {
        return when (val result = runtime.generationCoordinator.cancelGeneration(audioAssetId)) {
            is AudioGenerationCancelResult.Canceled -> success("已取消音频生成")
            is AudioGenerationCancelResult.Failure -> failure(result.errorCode.name)
        }
    }

    override suspend fun requestDelete(audioAssetId: String): AudioAssetWorkspaceOperationResult {
        return when (val result = runtime.lifecycleService.requestDelete(
            RequestAudioAssetDeleteCommand(
                audioAssetId = audioAssetId,
                requestedAt = nowProvider(),
                userConfirmed = true,
            ),
        )) {
            is AudioAssetDeleteRequestResult.Requested -> success("已记录受控删除请求")
            is AudioAssetDeleteRequestResult.AlreadyRequested -> success("删除请求已存在")
            is AudioAssetDeleteRequestResult.Failure -> failure(result.errorCode.name)
            AudioAssetDeleteRequestResult.ConfirmationRequired -> failure("CONFIRMATION_REQUIRED")
        }
    }

    override fun play(asset: AudioAssetRecord): AudioAssetWorkspaceOperationResult {
        if (asset.fileState != AudioFileState.AVAILABLE || asset.purgeRequestedAt != null) {
            return failure("AUDIO_NOT_AVAILABLE")
        }
        val path = asset.storagePath ?: return failure("FILE_MISSING")
        return runtime.playbackManager.play(asset.id, path).toWorkspaceResult("正在播放")
    }

    override fun pause(): AudioAssetWorkspaceOperationResult =
        runtime.playbackManager.pause().toWorkspaceResult("已暂停")

    override fun resume(): AudioAssetWorkspaceOperationResult =
        runtime.playbackManager.resume().toWorkspaceResult("继续播放")

    override fun stop(): AudioAssetWorkspaceOperationResult =
        runtime.playbackManager.stop().toWorkspaceResult("已停止")

    override fun playbackState(): AudioAssetPlaybackState = runtime.playbackManager.state

    override fun release() {
        runtime.playbackManager.release()
    }

    private fun AudioAssetPlaybackResult.toWorkspaceResult(
        successMessage: String,
    ): AudioAssetWorkspaceOperationResult = when (this) {
        AudioAssetPlaybackResult.STARTED,
        AudioAssetPlaybackResult.PAUSED,
        AudioAssetPlaybackResult.RESUMED,
        AudioAssetPlaybackResult.STOPPED,
        -> success(successMessage)
        is AudioAssetPlaybackResult.Failure -> failure(errorCode.name)
    }

    private fun success(message: String) = AudioAssetWorkspaceOperationResult.Success(message)
    private fun failure(code: String) = AudioAssetWorkspaceOperationResult.Failure(code)

    private companion object {
        const val ESTIMATED_OUTPUT_BYTES = 16L * 1024L * 1024L
        const val MINIMUM_RESERVATION_BYTES = 1L * 1024L * 1024L
        const val SAFETY_MARGIN_BYTES = 8L * 1024L * 1024L
    }
}
