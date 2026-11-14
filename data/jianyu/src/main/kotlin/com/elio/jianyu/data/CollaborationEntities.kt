package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

class CollaborationConverters {
    @TypeConverter
    fun crossDiscussionStatusToStorageValue(status: CrossDiscussionStatus): String =
        status.storageValue

    @TypeConverter
    fun storageValueToCrossDiscussionStatus(value: String): CrossDiscussionStatus =
        CrossDiscussionStatus.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown cross discussion status: $value")
}
