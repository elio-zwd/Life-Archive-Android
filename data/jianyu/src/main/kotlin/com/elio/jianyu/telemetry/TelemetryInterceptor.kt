package com.elio.jianyu.telemetry

import com.elio.jianyu.network.AiManager
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.*
import okio.*

/** 应用请求预先持有同一引用，避免 BridgeInterceptor 恢复原请求时丢失网络层 tag。 */
class DiagnosticExchangeTag {
    @Volatile var id: String? = null
        internal set
}

/** Factory 在创建 Call 的调用线程执行，从而捕获 Retrofit 异步调用的协程上下文。 */
class DiagnosticCallListener(private val call: Call, val trace: DiagnosticContext?) : EventListener() {
    companion object {
        private val calls = ConcurrentHashMap<Call, DiagnosticCallListener>()
        fun get(call: Call) = calls[call]
    }
    private val started = System.currentTimeMillis()
    private val stages = StringBuilder()
    var hadExchange = false
    init { calls[call] = this }
    private fun stage(value: String) { synchronized(stages) { stages.append("${System.currentTimeMillis() - started}ms $value\n") } }
    override fun dnsStart(call: Call, domainName: String) { stage("DNS 开始 $domainName") }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { stage("DNS 完成 ${inetAddressList.joinToString { it.hostAddress.orEmpty() }}") }
    override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { stage("连接 $inetSocketAddress $proxy") }
    override fun secureConnectStart(call: Call) { stage("TLS 开始") }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { stage("TLS 完成 ${handshake?.tlsVersion}") }
    override fun connectionAcquired(call: Call, connection: Connection) { stage("连接已获取 ${connection.protocol()}") }
    override fun callEnd(call: Call) { calls.remove(call) }
    override fun callFailed(call: Call, ioe: IOException) {
        if (!hadExchange && TelemetryRepository.captureEnabled()) {
            val id = UUID.randomUUID().toString()
            TelemetryRepository.record(TelemetryEvent(id, started, System.currentTimeMillis() - started,
                DiagnosticCredentials.url(call.request().url.toString()), failureType = if (call.isCanceled()) "CANCELLED" else "NETWORK",
                errorMessage = DiagnosticCredentials.text(ioe.stackTraceToString(), DiagnosticCredentials.secrets(call.request())), operationId = trace?.operationId ?: id,
                sessionId = trace?.sessionId, questionId = trace?.questionId, characterId = trace?.characterId, purpose = trace?.purpose,
                model = trace?.model, keyId = trace?.keyId, retryCount = TelemetryRepository.nextAttempt(trace?.operationId ?: id),
                method = call.request().method, provider = if (call.request().url.host.contains("deepseek")) "DeepSeek" else "Gemini",
                connection = stages.toString(), captureState = "INCOMPLETE"))
        }
        calls.remove(call)
    }
    fun connectionInfo() = synchronized(stages) { stages.toString() }
    fun isCancelled() = call.isCanceled()
}

/** 每次真实网络交换记录一次，正文通过 tee 采集，不额外调用 RequestBody.writeTo。 */
class TelemetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!TelemetryRepository.captureEnabled() || chain.request().header("Upgrade")?.equals("websocket", true) == true) return chain.proceed(chain.request())
        val listener = DiagnosticCallListener.get(chain.call())
        listener?.hadExchange = true
        val trace = chain.request().tag(DiagnosticContext::class.java) ?: listener?.trace ?: DiagnosticContext.current()
        val recorder = ExchangeRecorder(chain.request(), trace, listener)
        val original = chain.request()
        val originalBody = original.body
        val exchange = original.tag(DiagnosticExchangeTag::class.java) ?: DiagnosticExchangeTag()
        exchange.id = recorder.id
        val builder = original.newBuilder().tag(DiagnosticExchangeTag::class.java, exchange)
        if (originalBody != null) builder.method(original.method, object : RequestBody() {
            override fun contentType() = originalBody.contentType()
            override fun contentLength() = originalBody.contentLength()
            override fun isDuplex() = originalBody.isDuplex()
            override fun isOneShot() = originalBody.isOneShot()
            override fun writeTo(sink: BufferedSink) {
                val tee = object : ForwardingSink(sink) {
                    override fun write(source: Buffer, byteCount: Long) {
                        var offset = 0L
                        while (offset < byteCount) {
                            val copy = Buffer(); val count = minOf(8192L, byteCount - offset)
                            source.copyTo(copy, offset, count); recorder.request(copy.readByteArray()); offset += count
                        }
                        super.write(source, byteCount)
                    }
                }.buffer()
                try { originalBody.writeTo(tee); tee.flush() } finally { recorder.requestEnd() }
            }
        })
        try {
            val response = chain.proceed(builder.build())
            recorder.headers(response)
            val body = response.body ?: run { recorder.finish(true); return response }
            val wrapped = object : ResponseBody() {
                private val input = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long = try {
                        val n = super.read(sink, byteCount)
                        if (n > 0) {
                            val copy = Buffer(); sink.copyTo(copy, sink.size - n, n); recorder.response(copy.readByteArray())
                        } else if (n == -1L) recorder.finish(true)
                        n
                    } catch (error: IOException) { recorder.fail(error); throw error }
                    override fun close() { try { super.close() } finally { recorder.finish(false) } }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = input
            }
            return response.newBuilder().body(wrapped).build()
        } catch (error: Exception) { recorder.fail(error); throw error }
    }
}

private class ExchangeRecorder(request: Request, trace: DiagnosticContext?, private val listener: DiagnosticCallListener?) {
    val id = UUID.randomUUID().toString()
    private val started = System.currentTimeMillis()
    private var version = 0L
    private val full = TelemetryRepository.currentLevel() == TelemetryLevel.CONTENT_DEBUG
    private val ended = AtomicBoolean()
    private val secrets = DiagnosticCredentials.secrets(request)
    private val requestMask = DiagnosticByteMasker(secrets)
    private val responseMask = DiagnosticByteMasker(secrets)
    private var responseBytes = 0L
    private var expectedBytes = -1L
    private val requestPrefix = Buffer()
    private val responseTail = Buffer()
    private var interrupted = false
    private var event = TelemetryEvent(id, started, 0, DiagnosticCredentials.url(request.url.toString()),
        model = trace?.model ?: Regex("models/([^:/]+)").find(request.url.encodedPath)?.groupValues?.get(1),
        keyId = trace?.keyId ?: secrets.firstNotNullOfOrNull { AiManager.findKeyIdOrNull(it) },
        operationId = trace?.operationId ?: id, sessionId = trace?.sessionId, questionId = trace?.questionId,
        characterId = trace?.characterId, purpose = trace?.purpose, retryCount = trace?.attempt ?: 0,
        provider = if (request.url.host.contains("deepseek")) "DeepSeek" else "Gemini", method = request.method,
        requestHeaders = if (full) headersJson(request.headers) else null,
        requestFile = if (full && request.body != null) TelemetryRepository.payloadName(id, "request") else null,
        captureState = "RECORDING")
    init { event = event.copy(retryCount = TelemetryRepository.nextAttempt(event.operationId)); version = TelemetryRepository.record(event) }
    private fun mask(value: String) = DiagnosticCredentials.text(value, secrets)
    private fun headersJson(headers: Headers): String = org.json.JSONArray().apply {
        for (i in 0 until headers.size) put(org.json.JSONObject().put("name", headers.name(i)).put("value", mask(DiagnosticCredentials.header(headers.name(i), headers.value(i)))))
    }.toString()
    fun request(bytes: ByteArray) {
        if (requestPrefix.size < 65536) requestPrefix.write(bytes, 0, minOf(bytes.size, (65536 - requestPrefix.size).toInt()))
        if (event.model == null) Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(requestPrefix.clone().readUtf8())?.let { event = event.copy(model = it.groupValues[1]) }
        if (full) write(event.requestFile, requestMask.accept(bytes))
    }
    fun requestEnd() { if (full) write(event.requestFile, requestMask.accept(byteArrayOf(), true)) }
    fun headers(response: Response) {
        expectedBytes = response.body?.contentLength() ?: -1
        val encoding = response.header("Content-Encoding")
        event = event.copy(statusCode = response.code, protocol = response.protocol.toString(), responseHeaders = if (full) headersJson(response.headers) else null,
            contentType = response.header("Content-Type"), responseEncoding = encoding,
            responseFile = if (full) TelemetryRepository.payloadName(id, if (encoding.equals("gzip", true)) "response.gzip" else "response") else null,
            streamEventsFile = if (full && response.header("Content-Type").orEmpty().contains("text/event-stream")) TelemetryRepository.payloadName(id, "events") else null,
            connection = listener?.connectionInfo(), firstByteMs = System.currentTimeMillis() - started,
            failureType = when (response.code) { 429 -> "RATE_LIMITED"; in 400..499 -> "HTTP_4XX"; in 500..599 -> "HTTP_5XX"; else -> null })
        TelemetryRepository.record(event)
    }
    fun response(bytes: ByteArray) {
        responseBytes += bytes.size
        responseTail.write(bytes)
        if (responseTail.size > 65536) responseTail.skip(responseTail.size - 65536)
        if (full) write(event.responseFile, if (event.responseEncoding.equals("gzip", true)) bytes else responseMask.accept(bytes))
    }
    private fun write(name: String?, bytes: ByteArray) {
        if (name != null && bytes.isNotEmpty() && !TelemetryRepository.append(id, version, name, bytes, secrets = secrets)) interrupted = true
    }
    fun fail(error: Throwable) {
        event = event.copy(failureType = when {
            error is java.util.concurrent.CancellationException || listener?.isCancelled() == true -> "CANCELLED"
            error is java.io.InterruptedIOException -> "TIMEOUT"
            else -> "NETWORK"
        }, errorMessage = mask(error.stackTraceToString()))
        finish(false)
    }
    fun finish(eof: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        if (full && !event.responseEncoding.equals("gzip", true)) write(event.responseFile, responseMask.accept(byteArrayOf(), true))
        if (event.responseEncoding == null) {
            val tail = responseTail.readUtf8()
            fun tokens(names: String): Int? = Regex("\"(?:$names)\"\\s*:\\s*(\\d+)").findAll(tail).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            event = event.copy(inputTokens = tokens("prompt_tokens|total_input_tokens|input_tokens"), outputTokens = tokens("completion_tokens|total_output_tokens|output_tokens"))
        }
        val complete = !interrupted && (eof || expectedBytes >= 0 && responseBytes == expectedBytes)
        TelemetryRepository.complete(event.copy(durationMs = System.currentTimeMillis() - started,
            captureState = if (complete) "COMPLETE" else "INCOMPLETE", captureError = if (complete) null else "业务读取在响应结束前停止；已保留收到的字节"), version, secrets)
    }
}
