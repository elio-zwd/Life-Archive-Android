package com.elio.lifearchive.core.preferences

import androidx.datastore.preferences.core.MutablePreferences
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
@Serializable enum class RemoteLoading { MANUAL, AUTOMATIC }
@Serializable enum class DocumentTheme { BLUE, WARM, GRAPHITE }
@Serializable enum class VideoPromptType { GENERAL, HOW_TO, EXPLANATION, CHOOSING }

// 四份提示词即使包含需 JSON 转义的字符，也必须能装入设置备份。
const val MAX_VIDEO_PROMPT_LENGTH = 16_000

@Serializable
data class UserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1f,
    val remoteLoading: RemoteLoading = RemoteLoading.MANUAL,
    val documentTheme: DocumentTheme? = null,
    val videoPrompts: Map<VideoPromptType, String> = emptyMap(),
) {
    init {
        require(fontScale.isFinite() && fontScale in 0.5f..3f)
        require(videoPrompts.values.all { it.isNotBlank() && it.length <= MAX_VIDEO_PROMPT_LENGTH }) { "提示词不能为空，且不能超过 $MAX_VIDEO_PROMPT_LENGTH 个字符" }
    }
}

fun UserPreferences.resolvedDocumentTheme(dark: Boolean): DocumentTheme =
    documentTheme ?: if (dark) DocumentTheme.GRAPHITE else DocumentTheme.BLUE

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setTheme(theme: ThemeMode)
    suspend fun setFontScale(scale: Float)
    suspend fun setRemoteLoading(mode: RemoteLoading) { error("此设置实现尚未提供远程加载能力") }
    suspend fun setDocumentTheme(theme: DocumentTheme) { error("此设置实现尚未提供文档主题能力") }
    /** null 表示移除用户覆盖值，使用应用内置模板。 */
    suspend fun setVideoPrompt(type: VideoPromptType, text: String?) { error("此设置实现尚未提供视频提示词能力") }
}

/** 每个文件只允许一个存活实例；关闭实例后须等待 scope 完成再重新打开同一文件。 */
class PreferencesStore(file: File, private val gate: StorageGate) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { file }
    private val themeKey = stringPreferencesKey("theme")
    private val fontKey = floatPreferencesKey("font_scale")
    private val remoteKey = stringPreferencesKey("remote_loading")
    private val documentThemeKey = stringPreferencesKey("document_theme")
    private val videoPromptsKey = stringPreferencesKey("video_prompts")
    // 旧备份的未选择状态必须跨恢复与后续写入保留。
    private val unselectedDocumentTheme = "__FOLLOW_GLOBAL__"
    private val values = store.data.map { prefs ->
        val documentTheme = when (val stored = prefs[documentThemeKey]) {
            null -> if (prefs.asMap().isEmpty()) DocumentTheme.BLUE else null
            unselectedDocumentTheme -> null
            else -> DocumentTheme.valueOf(stored)
        }
        UserPreferences(
            prefs[themeKey]?.let(ThemeMode::valueOf) ?: ThemeMode.SYSTEM,
            prefs[fontKey] ?: 1f,
            prefs[remoteKey]?.let(RemoteLoading::valueOf) ?: RemoteLoading.MANUAL,
            documentTheme,
            prefs[videoPromptsKey]?.let { Json.decodeFromString<Map<VideoPromptType, String>>(it) }.orEmpty(),
        )
    }

    private fun MutablePreferences.rememberNewDocumentDefault() {
        if (asMap().isEmpty()) this[documentThemeKey] = DocumentTheme.BLUE.name
    }

    val repository: PreferencesRepository = object : PreferencesRepository {
        override val preferences: Flow<UserPreferences> = flow {
            gate.access { Unit }
            emitAll(values.map { gate.access { values.first() } })
        }
        override suspend fun setTheme(theme: ThemeMode) = gate.access {
            store.edit { it.rememberNewDocumentDefault(); it[themeKey] = theme.name }
            Unit
        }
        override suspend fun setFontScale(scale: Float) = gate.access {
            UserPreferences(fontScale = scale)
            store.edit { it.rememberNewDocumentDefault(); it[fontKey] = scale }
            Unit
        }
        override suspend fun setRemoteLoading(mode: RemoteLoading) = gate.access {
            store.edit { it.rememberNewDocumentDefault(); it[remoteKey] = mode.name }
            Unit
        }
        override suspend fun setDocumentTheme(theme: DocumentTheme) = gate.access {
            store.edit { it[documentThemeKey] = theme.name }
            Unit
        }
        override suspend fun setVideoPrompt(type: VideoPromptType, text: String?) = gate.access {
            if (text != null) UserPreferences(videoPrompts = mapOf(type to text))
            store.edit {
                val prompts = it[videoPromptsKey]?.let { raw -> Json.decodeFromString<Map<VideoPromptType, String>>(raw) }.orEmpty().toMutableMap()
                if (text == null) prompts.remove(type) else prompts[type] = text
                it.rememberNewDocumentDefault()
                if (prompts.isEmpty()) it.remove(videoPromptsKey) else it[videoPromptsKey] = Json.encodeToString(prompts)
            }
            Unit
        }
    }

    // 历史 format=1 快照可能省略默认字段；新快照显式写入 format=2。
    @Serializable private data class Snapshot(val format: Int = 1, val preferences: UserPreferences)

    val backupParticipant: BackupParticipant = object : BackupParticipant {
        override val id = "preferences"
        override suspend fun snapshot(directory: File) {
            atomicWrite(File(directory, "settings.json"), Json.encodeToString(Snapshot(format = 2, preferences = values.first())).toByteArray())
        }
        override suspend fun validate(directory: File) {
            require(directory.list()?.toSet() == setOf("settings.json"))
            val file = File(directory, "settings.json")
            require(file.length() in 1..524288)
            require(Json.decodeFromString<Snapshot>(file.readText()).format in 1..2)
        }
        override suspend fun restore(directory: File) {
            validate(directory)
            val value = Json.decodeFromString<Snapshot>(File(directory, "settings.json").readText()).preferences
            store.edit {
                it.clear()
                it[themeKey] = value.theme.name
                it[fontKey] = value.fontScale
                it[remoteKey] = value.remoteLoading.name
                it[documentThemeKey] = value.documentTheme?.name ?: unselectedDocumentTheme
                if (value.videoPrompts.isNotEmpty()) it[videoPromptsKey] = Json.encodeToString(value.videoPrompts)
            }
        }
    }

    override fun close() { scope.cancel() }
    suspend fun closeAndJoin() { close(); job.join() }
}
