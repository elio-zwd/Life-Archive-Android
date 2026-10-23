package com.elio.lifearchive.feature.editor

import com.elio.lifearchive.domain.notes.*
import org.json.JSONObject

/** 不向笔记 HTML 或离线网页框架开放私有路径，只允许本地主页面提交有界派生物。 */
internal object ReaderCacheMessages {
    fun allowed(mainFrame: Boolean, origin: String, revision: String, message: JSONObject): Boolean =
        mainFrame && origin == "https://appassets.androidplatform.net" && message.optString("revision") == revision

    suspend fun put(message: JSONObject, cache: ReaderCacheRepository): Boolean {
        val kind = runCatching { ReaderArtifactKind.valueOf(message.getString("kind")) }.getOrNull() ?: return false
        if (kind == ReaderArtifactKind.HTML) return false
        val digest = message.optString("key")
        if (!Regex("[a-f0-9]{64}").matches(digest)) return false
        val value = message.optString("value")
        if (value.length > 8 * 1024 * 1024) return false
        val token = ReaderCacheToken(message.optString("namespace"))
        if (cache.token() != token) return false
        return cache.put(token, ReaderArtifactKey(kind, digest), value)
    }
}
