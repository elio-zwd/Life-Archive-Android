package com.elio.jianyu.lifecycle



enum class IssuePurgeFileFailureCode(val storageValue: String) {
    AUDIO_DELETE_REQUEST_FAILED("purge_audio_delete_request_failed"),
    FORMAL_FILE_DELETE_FAILED("purge_file_delete_failed"),
    TEMPORARY_FILE_DELETE_FAILED("purge_temporary_file_delete_failed"),
    STORAGE_UNAVAILABLE("purge_storage_unavailable"),
}

sealed interface IssuePurgeFileCleanupResult {
    data class Success(
        val assetCount: Int,
        val formalFileCount: Int,
        val temporaryFileCount: Int,
    ) : IssuePurgeFileCleanupResult

    data class Failure(
        val code: IssuePurgeFileFailureCode,
        val stableAudioAssetId: String?,
    ) : IssuePurgeFileCleanupResult
}

fun interface IssuePurgeFileCleanup {
    suspend fun clean(issueId: String, requestedAt: Long): IssuePurgeFileCleanupResult
}

/**
 * 只清理正式 AudioAsset 指向的受控文件及该资产的 `.part` 文件。
 *
 * 孤儿文件不在本流程中删除；绝对路径、路径穿越和其他 Issue 文件由 [AudioFileStore] 拒绝。
 */
