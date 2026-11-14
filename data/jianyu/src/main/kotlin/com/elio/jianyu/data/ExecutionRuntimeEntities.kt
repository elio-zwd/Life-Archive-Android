package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

class ExecutionRuntimeConverters {
    @TypeConverter
    fun participantStatusToStorageValue(status: ExecutionParticipantStatus): String =
        status.storageValue

    @TypeConverter
    fun storageValueToParticipantStatus(value: String): ExecutionParticipantStatus =
        ExecutionParticipantStatus.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown execution participant status: $value")
}
