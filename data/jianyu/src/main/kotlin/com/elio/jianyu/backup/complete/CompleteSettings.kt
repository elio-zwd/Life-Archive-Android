package com.elio.jianyu.backup.complete

import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal object CompleteSettings {
    val names = setOf("jianyu_app_preferences", "roundtable_settings", "conversation_session_preferences", "dialog_reading_state", "official_skill_catalog_preferences_v1", "ai_runtime_configuration")

    fun capture(preferences: (String) -> SharedPreferences): String {
        val profiles = JSONObject()
        for (name in names.sorted()) {
            val entries = JSONObject()
            for ((key, value) in preferences(name).all.toSortedMap()) {
                require(allowed(name, key)) { "未支持的设置项：$name.$key；完整导出已停止" }
                val item = JSONObject()
                when (value) {
                    is String -> item.put("type", "string").put("value", value)
                    is Boolean -> item.put("type", "boolean").put("value", value)
                    is Int -> item.put("type", "int").put("value", value)
                    is Long -> item.put("type", "long").put("value", value)
                    is Float -> { require(value.isFinite()); item.put("type", "float").put("value", value.toDouble()) }
                    is Set<*> -> { require(value.all { it is String }); item.put("type", "strings").put("value", JSONArray(value.filterIsInstance<String>().sorted())) }
                    else -> error("未支持的设置值：$name.$key")
                }
                entries.put(key, item)
            }
            profiles.put(name, entries)
        }
        return profiles.toString().also(::validate)
    }

    fun validate(raw: String): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
        val profiles = JSONObject(raw)
        require(profiles.keys().asSequence().toSet() == names) { "设置覆盖范围不匹配" }
        for (name in names) {
            val entries = profiles.getJSONObject(name)
            for (key in entries.keys()) {
                require(allowed(name, key)) { "不支持或禁止恢复的设置：$name.$key" }
                val item = entries.getJSONObject(key)
                require(item.getString("type") == expectedType(name, key)) { "设置类型不匹配：$name.$key" }
                when (item.getString("type")) {
                    "string" -> require(item.get("value") is String)
                    "boolean" -> require(item.get("value") is Boolean)
                    "int" -> require(item.get("value") is Int || item.get("value") is Long && item.getLong("value") in Int.MIN_VALUE..Int.MAX_VALUE)
                    "long" -> require(item.get("value") is Int || item.get("value") is Long)
                    "float" -> require(item.get("value") is Number && item.getDouble("value").toFloat().isFinite())
                    "strings" -> item.getJSONArray("value").let { array ->
                        require(array.length() <= 10000)
                        for (index in 0 until array.length()) require(array.get(index) is String)
                    }
                    else -> error("不支持的设置类型")
                }
            }
        }
        return profiles
    }

    fun apply(raw: String, preferences: (String) -> SharedPreferences) {
        val profiles = validate(raw)
        for (name in names.sorted()) {
            val editor = preferences(name).edit().clear()
            val entries = profiles.getJSONObject(name)
            for (key in entries.keys()) {
                val item = entries.getJSONObject(key)
                when (item.getString("type")) {
                    "string" -> editor.putString(key, item.getString("value"))
                    "boolean" -> editor.putBoolean(key, item.getBoolean("value"))
                    "int" -> editor.putInt(key, item.getInt("value"))
                    "long" -> editor.putLong(key, item.getLong("value"))
                    "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
                    "strings" -> editor.putStringSet(key, item.getJSONArray("value").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() })
                }
            }
            check(editor.commit()) { "无法写入见域设置；须回滚" }
        }
    }

    fun write(file: File, raw: String) {
        validate(raw)
        file.parentFile!!.mkdirs()
        FileOutputStream(file).use { output -> output.write(raw.toByteArray(Charsets.UTF_8)); output.fd.sync() }
    }

    // 与各设置读取端保持一致，预览时拒绝类型错误，避免提交后才触发 ClassCastException。
    private fun expectedType(name: String, key: String): String = when (name) {
        "jianyu_app_preferences" -> if (key in setOf("theme_mode", "font_size_mode", "content_density_mode")) "string" else "boolean"
        "roundtable_settings" -> if (key.startsWith("is_")) "boolean" else "string"
        "conversation_session_preferences" -> if (key == "archived_session_ids") "strings" else "string"
        "official_skill_catalog_preferences_v1" -> if (key == "favorite_official_skill_ids") "strings" else "string"
        "dialog_reading_state" -> when {
            key.matches(Regex("session_[0-9]+_roles")) -> "boolean"
            key.matches(Regex("session_[0-9]+_(read|expanded)")) -> "strings"
            key.matches(Regex("session_[0-9]+_(scroll_offset|offset_.+)")) -> "int"
            key.matches(Regex("session_[0-9]+_(scroll_progress|progress_.+)")) -> "float"
            else -> "string"
        }
        else -> "string"
    }

    private fun allowed(name: String, key: String): Boolean = when (name) {
        "jianyu_app_preferences" -> key in setOf("theme_mode", "font_size_mode", "content_density_mode", "reduced_motion", "high_contrast_text", "show_message_timestamps", "confirm_sensitive_context")
        "roundtable_settings" -> key in setOf("is_auto_next_enabled", "is_semantic_routing_enabled", "search_mode", "thinking_intensity")
        "conversation_session_preferences" -> key == "archived_session_ids" || key.matches(Regex("session_[0-9]+_participant_ids"))
        "dialog_reading_state" -> key.matches(Regex("session_[0-9]+_(roles|page_.+|read|expanded|offset_.+|progress_.+|scroll_key|scroll_offset|scroll_progress)"))
        "official_skill_catalog_preferences_v1" -> key in setOf("favorite_official_skill_ids", "recent_official_skill_uses")
        "ai_runtime_configuration" -> key == "provider" || key.matches(Regex("model\\.[A-Z_]+"))
        else -> false
    }
}
