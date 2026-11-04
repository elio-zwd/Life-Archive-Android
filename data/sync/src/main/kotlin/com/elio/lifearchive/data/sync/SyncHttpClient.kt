package com.elio.lifearchive.data.sync

import com.elio.lifearchive.domain.notes.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun normalizeSyncAddress(value: String): String {
    val uri = URI(value.trim().trimEnd('/'))
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入电脑显示的完整 HTTP 地址" }
    require(uri.path.isEmpty() || uri.path == "/api/sync") { "电脑地址路径应为 /api/sync" }
    return "${uri.scheme}://${uri.rawAuthority}/api/sync"
}
@Serializable internal data class PeerManifest(val protocol: Int, val libraryId: String, val files: List<SyncEntry>, val issues: List<SyncIssue> = emptyList())
@Serializable internal data class PeerIdentity(val protocol: Int, val libraryId: String)
@Serializable internal data class PairResponse(val protocol: Int, val libraryId: String, val token: String)
@Serializable internal data class SyncChange(val operationId: String, val expected: SyncEntry?, val desired: SyncEntry?)
@Serializable private data class CommitResponse(val committed: Boolean, val entry: SyncEntry?)
internal class SyncHttpException(val status: Int, message: String) : Exception(message)

internal class SyncHttpClient(private val address: String, private val token: String = "") {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    var transferred: Long = 0; private set
    private fun open(path: String, method: String): HttpURLConnection = (URL(address + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 10000; readTimeout = 30000; instanceFollowRedirects = false
        if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
    }
    private fun text(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 4 * 1024 * 1024) { "同步清单过大" }; output.write(buffer, 0, n) }
            output.toString("UTF-8")
        }.orEmpty()
        if (code !in 200..299) throw SyncHttpException(code, if (code == 401) "电脑配对已失效，请重新配对" else body.take(500))
        return body
    }
    fun pair(code: String): PairResponse {
        val connection = open("/pair", "POST")
        try {
            val bytes = json.encodeToString(mapOf("code" to code)).toByteArray()
            connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return json.decodeFromString(text(connection))
        } finally { connection.disconnect() }
    }
    fun manifest(): PeerManifest {
        val connection = open("/manifest", "GET")
        try { return json.decodeFromString(text(connection)) } finally { connection.disconnect() }
    }
    fun identity(): PeerIdentity {
        val connection = open("/identity", "GET")
        try { return json.decodeFromString(text(connection)) } finally { connection.disconnect() }
    }
    suspend fun download(entry: SyncEntry, target: File) {
        val connection = open("/file?entry=" + URLEncoder.encode(json.encodeToString(entry), "UTF-8"), "GET")
        try {
            if (connection.responseCode !in 200..299) { text(connection); error("无法读取文件") }
            val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= entry.size); hash.update(buffer, 0, n); output.write(buffer, 0, n)
                }
            } }
            require(total == entry.size && hash.digest().joinToString("") { "%02x".format(it) } == entry.hash) { "电脑文件在传输中变化或校验失败" }
            transferred += total
        } finally { connection.disconnect() }
    }
    suspend fun apply(change: SyncChange, source: File?) {
        val connection = open("/changes", "POST")
        try {
            connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("X-Sync-Change", Base64.getEncoder().encodeToString(json.encodeToString(change).toByteArray()))
            val size = source?.length() ?: 0L; connection.setFixedLengthStreamingMode(size)
            connection.outputStream.use { output -> source?.inputStream()?.use { input ->
                val buffer = ByteArray(65536)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            } }
            val result = json.decodeFromString<CommitResponse>(text(connection))
            require(result.committed && result.entry == change.desired) { "电脑未确认提交" }
            transferred += size
        } finally { connection.disconnect() }
    }
}
