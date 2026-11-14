package com.elio.jianyu.telemetry

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** 仅处理认证字段和实际使用的凭据，不过滤用户文本、邮箱、电话或会话标识。 */
object DiagnosticCredentials {
    private val authHeaders = setOf("authorization", "proxy-authorization", "x-goog-api-key", "api-key", "x-api-key", "cookie", "set-cookie")
    fun header(name: String, value: String): String = if (name.lowercase() in authHeaders) "[AUTH_HIDDEN]" else value
    fun secrets(request: okhttp3.Request): List<String> = buildList {
        for (name in authHeaders) request.header(name)?.let { value ->
            add(value)
            if (name == "authorization" || name == "proxy-authorization") add(value.substringAfter(' ', value))
            if (name == "cookie") value.split(';').forEach { pair -> add(pair.substringAfter('=').trim()) }
        }
        for (name in listOf("key", "api_key", "access_token", "token")) request.url.queryParameter(name)?.let(::add)
    }
    fun url(value: String): String = value.replace(
        Regex("(?i)([?&](?:key|api_key|access_token|token)=)[^&#\\s]*"), "$1[AUTH_HIDDEN]",
    )
    fun text(value: String, secrets: List<String> = emptyList()): String {
        val result = body(value, secrets)
        return url(result).replace(Regex("\\bAIza[A-Za-z0-9_-]{20,}\\b"), "[AUTH_HIDDEN]")
            .replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+"), "$1[AUTH_HIDDEN]")
    }
    fun body(value: String, secrets: List<String>): String {
        var result = value
        secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { result = result.replace(it, "[AUTH_HIDDEN]") }
        return result
    }
}

/** 延迟输出尾部，保证跨网络分块的凭据也在落盘前替换；非认证字节保持不变。 */
internal class DiagnosticByteMasker(secrets: List<String>) {
    var replaced: Boolean = false
        private set
    private val secrets = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        .map { it.toByteArray(StandardCharsets.UTF_8) }
    private var pending = ByteArray(0)
    fun accept(bytes: ByteArray, end: Boolean = false): ByteArray {
        val data = pending + bytes
        val reserve = if (end) 0 else (secrets.maxOfOrNull { it.size } ?: 1) - 1
        val safeEnd = (data.size - reserve).coerceAtLeast(0)
        val output = ByteArrayOutputStream()
        var position = 0
        while (position < safeEnd) {
            val secret = secrets.firstOrNull { candidate ->
                position + candidate.size <= data.size && candidate.indices.all { data[position + it] == candidate[it] }
            }
            if (secret != null) { replaced = true; output.write("[AUTH_HIDDEN]".toByteArray()); position += secret.size }
            else { output.write(data[position].toInt()); position++ }
        }
        pending = data.copyOfRange(position, data.size)
        return output.toByteArray()
    }
}
