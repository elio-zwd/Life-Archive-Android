package com.elio.jianyu.audio.assets

import java.io.File

import com.elio.jianyu.domain.AudioFileState

enum class AudioAssetPlaybackErrorCode {
    FILE_MISSING,
    PATH_REJECTED,
    PLAYER_FAILURE,
    INVALID_STATE,
    RELEASED,
}

sealed interface AudioAssetPlaybackState {
    data object Idle : AudioAssetPlaybackState

    data class Playing(val audioAssetId: String) : AudioAssetPlaybackState

    data class Paused(val audioAssetId: String) : AudioAssetPlaybackState

    data class Failed(
        val audioAssetId: String,
        val errorCode: AudioAssetPlaybackErrorCode,
    ) : AudioAssetPlaybackState

    data object Released : AudioAssetPlaybackState
}

sealed interface AudioAssetPlaybackResult {
    data object STARTED : AudioAssetPlaybackResult

    data object PAUSED : AudioAssetPlaybackResult

    data object RESUMED : AudioAssetPlaybackResult

    data object STOPPED : AudioAssetPlaybackResult

    data class Failure(
        val errorCode: AudioAssetPlaybackErrorCode,
    ) : AudioAssetPlaybackResult
}

/** Android MediaPlayer 或测试播放器需要实现的最小能力。 */
interface AudioAssetPlayer {
    fun start()

    fun pause()

    fun stop()

    fun release()
}

fun interface AudioAssetPlayerFactory {
    fun create(
        file: File,
        onCompletion: () -> Unit,
        onError: (Throwable) -> Unit,
    ): AudioAssetPlayer
}

/**
 * 以 audioAssetId 为身份的单活动音频播放状态机。
 *
 * 该组件只读取本地受控文件，不包含网络、缓存回填或来源对象写入能力。
 */
