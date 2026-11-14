package com.elio.lifearchive

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.hostSwitchDataStore by preferencesDataStore("life_host_switch")

internal data class SwitchPosition(val x: Float = 1f, val y: Float = .55f) {
    fun normalized() = SwitchPosition(if (x.isFinite()) x.coerceIn(0f, 1f) else 1f,
        if (y.isFinite()) y.coerceIn(0f, 1f) else .55f)
}

/** 仅此设备的悬浮位置；不属于任一业务的内容或恢复范围。 */
@Singleton
internal class SwitchPositionStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.applicationContext.hostSwitchDataStore
    val positions = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { SwitchPosition(it[X] ?: 1f, it[Y] ?: .55f).normalized() }
    suspend fun save(position: SwitchPosition) {
        val safe = position.normalized()
        store.edit { it[X] = safe.x; it[Y] = safe.y }
    }
    private companion object {
        val X = floatPreferencesKey("x_fraction")
        val Y = floatPreferencesKey("y_fraction")
    }
}
