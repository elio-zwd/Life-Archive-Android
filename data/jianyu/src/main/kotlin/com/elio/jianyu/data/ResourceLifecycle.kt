package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import androidx.room.TypeConverter

class ResourceLifecycleConverters {
    @TypeConverter
    fun contextSourceLifecycleToStorage(value: ContextSourceLifecycle): String = value.storageValue

    @TypeConverter
    fun storageToContextSourceLifecycle(value: String): ContextSourceLifecycle {
        return ContextSourceLifecycle.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("未知的上下文来源生命周期")
    }

    @TypeConverter
    fun snapshotContentStateToStorage(value: SnapshotContentState): String = value.storageValue

    @TypeConverter
    fun storageToSnapshotContentState(value: String): SnapshotContentState {
        return SnapshotContentState.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("未知的快照内容状态")
    }

    @TypeConverter
    fun audioFileStateToStorage(value: AudioFileState): String = value.storageValue

    @TypeConverter
    fun storageToAudioFileState(value: String): AudioFileState {
        return AudioFileState.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("未知的音频文件状态")
    }

    @TypeConverter
    fun issueLifecycleStateToStorage(value: IssueLifecycleState): String = value.storageValue

    @TypeConverter
    fun storageToIssueLifecycleState(value: String): IssueLifecycleState {
        return IssueLifecycleState.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("未知的议题生命周期状态")
    }
}
