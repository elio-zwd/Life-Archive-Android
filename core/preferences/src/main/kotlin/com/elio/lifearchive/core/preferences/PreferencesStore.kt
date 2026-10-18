package com.elio.lifearchive.core.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.elio.lifearchive.core.backup.BackupParticipant
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.backup.atomicWrite
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class UserPreferences(val theme: ThemeMode = ThemeMode.SYSTEM, val fontScale: Float = 1f) {
    init { require(fontScale.isFinite() && fontScale in 0.5f..3f) }
}

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setTheme(theme: ThemeMode)
    suspend fun setFontScale(scale: Float)
}

/** 每个文件只允许一个存活实例；关闭实例后须等待 scope 完成再重新打开同一文件。 */
class PreferencesStore(file: File, private val gate: StorageGate) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { file }
    private val themeKey = stringPreferencesKey("theme")
    private val fontKey = floatPreferencesKey("font_scale")
    private val values = store.data.map { prefs ->
        UserPreferences(prefs[themeKey]?.let(ThemeMode::valueOf) ?: ThemeMode.SYSTEM, prefs[fontKey] ?: 1f)
    }

    val repository: PreferencesRepository = object : PreferencesRepository {
        override val preferences: Flow<UserPreferences> = flow {
            gate.access { Unit }
            emitAll(values.map { gate.access { values.first() } })
        }
        override suspend fun setTheme(theme: ThemeMode) = gate.access { store.edit { it[themeKey] = theme.name }; Unit }
        override suspend fun setFontScale(scale: Float) = gate.access {
            UserPreferences(fontScale = scale)
            store.edit { it[fontKey] = scale }
            Unit
        }
    }

    @Serializable private data class Snapshot(val format: Int = 1, val preferences: UserPreferences)

    val backupParticipant: BackupParticipant = object : BackupParticipant {
        override val id = "preferences"
        override suspend fun snapshot(directory: File) {
            atomicWrite(File(directory, "settings.json"), Json.encodeToString(Snapshot(preferences = values.first())).toByteArray())
        }
        override suspend fun validate(directory: File) {
            require(directory.list()?.toSet() == setOf("settings.json"))
            val file = File(directory, "settings.json")
            require(file.length() in 1..65536)
            require(Json.decodeFromString<Snapshot>(file.readText()).format == 1)
        }
        override suspend fun restore(directory: File) {
            validate(directory)
            val value = Json.decodeFromString<Snapshot>(File(directory, "settings.json").readText()).preferences
            store.edit { it.clear(); it[themeKey] = value.theme.name; it[fontKey] = value.fontScale }
        }
    }

    override fun close() { scope.cancel() }
    suspend fun closeAndJoin() { close(); job.join() }
}
