package com.elio.jianyu.audio.assets
import com.elio.jianyu.domain.AudioFileState

class AudioAssetLifecycleService(
    private val repository: AudioAssetLifecycleRepositoryPort,
    private val scheduler: AudioGenerationSchedulerPort,
    private val fileStore: AudioFileStore,
) {
    suspend fun getAudioAsset(audioAssetId: String): AudioAssetRecord? {
        require(audioAssetId.isNotBlank()) { "音频资产 ID 不能为空" }
        return repository.getAudioAsset(audioAssetId)
    }

    suspend fun listAudioAssetsForIssue(issueId: String): List<AudioAssetRecord> {
        require(issueId.isNotBlank()) { "议题 ID 不能为空" }
        return repository.listAudioAssetsForIssue(issueId)
    }

    suspend fun listAudioAssetsForStage(
        issueId: String,
        stageId: String,
    ): List<AudioAssetRecord> {
        require(issueId.isNotBlank()) { "议题 ID 不能为空" }
        require(stageId.isNotBlank()) { "阶段 ID 不能为空" }
        return repository.listAudioAssetsForStage(issueId, stageId)
    }

    suspend fun reconcileFilesForIssue(issueId: String): AudioFileReconciliationResult {
        require(issueId.isNotBlank()) { "议题 ID 不能为空" }
        val assets = repository.listAudioAssetsForIssue(issueId)
        val globalReferencedPaths = referencedPathsForOrphanScan(assets)
        val markedMissing = mutableListOf<String>()
        val stale = mutableListOf<String>()

        assets.asSequence()
            .filter { it.fileState == AudioFileState.AVAILABLE }
            .forEach { asset ->
                val shouldMarkMissing = when (val path = asset.storagePath) {
                    null -> true
                    else -> when (fileStore.resolve(path)) {
                        is AudioFileResolution.Available -> false
                        is AudioFileResolution.Missing,
                        is AudioFileResolution.Rejected,
                        -> true
                    }
                }
                if (shouldMarkMissing) {
                    val changed = repository.markMissing(
                        audioAssetId = asset.id,
                        expectedState = AudioFileState.AVAILABLE,
                    )
                    if (changed) {
                        markedMissing += asset.id
                    } else {
                        stale += asset.id
                    }
                }
            }

        return AudioFileReconciliationResult(
            markedMissingAssetIds = markedMissing.sorted(),
            staleAssetIds = stale.sorted(),
            orphanReport = fileStore.scanOrphans(globalReferencedPaths),
        )
    }

    suspend fun inspectPurgeImpact(issueId: String): AudioPurgeImpact {
        require(issueId.isNotBlank()) { "议题 ID 不能为空" }
        val assets = repository.listAudioAssetsForIssue(issueId)
        val globalReferencedPaths = referencedPathsForOrphanScan(assets)
        val missingIds = linkedSetOf<String>()
        var referencedFileCount = 0
        var referencedFileBytes = 0L

        assets.forEach { asset ->
            if (asset.fileState == AudioFileState.MISSING) {
                missingIds += asset.id
            }
            val path = asset.storagePath ?: return@forEach
            when (val resolution = fileStore.resolve(path)) {
                is AudioFileResolution.Available -> {
                    referencedFileCount += 1
                    referencedFileBytes = safeAdd(
                        referencedFileBytes,
                        resolution.file.length().coerceAtLeast(0L),
                    )
                }
                is AudioFileResolution.Missing,
                is AudioFileResolution.Rejected,
                -> if (asset.fileState == AudioFileState.AVAILABLE) {
                    missingIds += asset.id
                }
            }
        }

        val pendingAssets = assets.filter { it.fileState == AudioFileState.PENDING }
        val uniqueWorkNames = pendingAssets.map { asset ->
            AudioGenerationWorkPolicy.plan(
                audioAssetId = asset.id,
                generationKey = asset.generationKey,
                requestKind = AudioWorkRequestKind.INITIAL,
            ).uniqueWorkName
        }.distinct().sorted()

        return AudioPurgeImpact(
            issueId = issueId,
            assetCount = assets.size,
            pendingAssetCount = pendingAssets.size,
            referencedFileCount = referencedFileCount,
            referencedFileBytes = referencedFileBytes,
            missingAssetIds = missingIds.sorted(),
            uniqueWorkNames = uniqueWorkNames,
            orphanReport = fileStore.scanOrphans(globalReferencedPaths),
        )
    }

    suspend fun requestDelete(
        command: RequestAudioAssetDeleteCommand,
    ): AudioAssetDeleteRequestResult {
        if (!command.userConfirmed) {
            return AudioAssetDeleteRequestResult.ConfirmationRequired
        }
        if (command.audioAssetId.isBlank() || command.requestedAt <= 0L) {
            return AudioAssetDeleteRequestResult.Failure(
                AudioGenerationErrorCode.REPOSITORY_REJECTED,
            )
        }

        val asset = repository.getAudioAsset(command.audioAssetId)
            ?: return AudioAssetDeleteRequestResult.Failure(
                AudioGenerationErrorCode.ASSET_NOT_FOUND,
            )
        if (asset.deletedAt != null) {
            return AudioAssetDeleteRequestResult.Failure(AudioGenerationErrorCode.DELETED)
        }
        if (asset.purgeRequestedAt != null) {
            return AudioAssetDeleteRequestResult.AlreadyRequested(asset.id)
        }

        val result = when (val writeResult = repository.requestDelete(
            PersistAudioDeleteRequestCommand(
                audioAssetId = asset.id,
                expectedState = asset.fileState,
                requestedAt = command.requestedAt,
            ),
        )) {
            is AudioDeleteWriteResult.Requested -> {
                AudioAssetDeleteRequestResult.Requested(writeResult.asset.id)
            }
            is AudioDeleteWriteResult.AlreadyRequested -> {
                AudioAssetDeleteRequestResult.AlreadyRequested(writeResult.asset.id)
            }
            AudioDeleteWriteResult.Rejected -> {
                return AudioAssetDeleteRequestResult.Failure(
                    AudioGenerationErrorCode.REPOSITORY_REJECTED,
                )
            }
        }

        if (asset.fileState == AudioFileState.PENDING) {
            val plan = AudioGenerationWorkPolicy.plan(
                audioAssetId = asset.id,
                generationKey = asset.generationKey,
                requestKind = AudioWorkRequestKind.INITIAL,
            )
            scheduler.cancel(plan.uniqueWorkName)
        }
        return result
    }

    private suspend fun referencedPathsForOrphanScan(
        issueAssets: List<AudioAssetRecord>,
    ): Set<String> {
        val referencedAssets = (repository as? AudioAssetGlobalReferenceRepositoryPort)
            ?.listAllAudioAssets()
            ?: issueAssets
        return referencedAssets.mapNotNull { asset ->
            asset.storagePath?.takeIf { it.isNotBlank() }
        }.toSet()
    }

    private fun safeAdd(
        current: Long,
        increment: Long,
    ): Long {
        return try {
            Math.addExact(current, increment)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}
