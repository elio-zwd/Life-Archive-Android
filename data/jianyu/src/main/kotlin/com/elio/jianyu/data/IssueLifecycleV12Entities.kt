package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

class IssueLifecycleV12Converters {
    @TypeConverter
    fun issueRelationTypeToStorage(value: IssueRelationType): String = value.storageValue

    @TypeConverter
    fun storageToIssueRelationType(value: String): IssueRelationType =
        IssueRelationType.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("未知的议题关系类型")

    @TypeConverter
    fun issuePurgeStateToStorage(value: IssuePurgeState): String = value.storageValue

    @TypeConverter
    fun storageToIssuePurgeState(value: String): IssuePurgeState =
        IssuePurgeState.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("未知的彻底清除状态")

    @TypeConverter
    fun issuePurgeFailurePhaseToStorage(value: IssuePurgeFailurePhase?): String? =
        value?.storageValue

    @TypeConverter
    fun storageToIssuePurgeFailurePhase(value: String?): IssuePurgeFailurePhase? =
        value?.let { stored ->
            IssuePurgeFailurePhase.entries.firstOrNull { it.storageValue == stored }
                ?: throw IllegalArgumentException("未知的彻底清除失败阶段")
        }
}
