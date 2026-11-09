package com.elio.lifearchive.core.markdown

import android.net.Uri
import org.json.JSONObject

/** 来源、主框架和修订都须一致；旧页面的消息不能写入当前文档的缓存或状态。 */
object MarkdownProtocol {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val MAX_MESSAGE_LENGTH = 8 * 1024 * 1024 + 2048
    fun acceptsMessage(mainFrame: Boolean, origin: String, revision: String, message: JSONObject): Boolean =
        mainFrame && origin == ORIGIN && message.optString("revision") == revision

    fun acceptsAction(uri: Uri, revision: String): Boolean =
        uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
            uri.port == -1 && uri.path == "/reader/action" && uri.getQueryParameter("revision") == revision

    fun isLocal(uri: Uri): Boolean = uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.port == -1

    /** 共用阅读器不能借静态资源处理器读取同 APK 内的角色或其他业务 assets。 */
    fun isStaticAsset(uri: Uri): Boolean {
        if (!isLocal(uri)) return false
        val path = uri.path.orEmpty()
        if (path.split('/').any { it == ".." || it == "." || '\\' in it || '\u0000' in it }) return false
        return path.startsWith("/reader/") || path in setOf("/covers/mountain.png", "/covers/lake.png", "/covers/forest.png", "/covers/paper.png")
    }
}
