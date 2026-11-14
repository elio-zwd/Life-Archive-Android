package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class StageAdvancementConverters {
    @TypeConverter
    fun measureToStorageValue(value: StageAdvancementMeasure): String = value.storageValue

    @TypeConverter
    fun storageValueToMeasure(value: String): StageAdvancementMeasure =
        StageAdvancementMeasure.entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unknown stage advancement measure: $value")
}
