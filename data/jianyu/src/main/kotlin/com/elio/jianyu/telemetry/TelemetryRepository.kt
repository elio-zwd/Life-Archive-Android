package com.elio.jianyu.telemetry

import com.elio.jianyu.storage.jianyuNoBackupFilesDir
import com.elio.jianyu.storage.jianyuPreferences

import android.content.Context
import android.net.Uri
import androidx.room.Room
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 索引与正文分开保存；所有变更在有界 IO 队列中串行执行。 */
object TelemetryRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(256))
    private val _events = MutableStateFlow<List<TelemetryEvent>>(emptyList())
    val events = _events.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val _payloadRevision = MutableStateFlow(0L)
    val payloadRevision = _payloadRevision.asStateFlow()
    private val _totalCount = MutableStateFlow(0)
    val totalCount = _totalCount.asStateFlow()
    private val _level = MutableStateFlow(TelemetryLevel.METADATA_ONLY)
    val level = _level.asStateFlow()
    private val _storageError = MutableStateFlow<String?>(null)
    val storageError = _storageError.asStateFlow()
    private val _usedBytes = MutableStateFlow(0L)
    val usedBytes = _usedBytes.asStateFlow()
    private val _capacity = MutableStateFlow(500L shl 20)
    val capacity = _capacity.asStateFlow()
    private val generation = AtomicLong()
    private val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blockedOperations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val operations = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val pendingBytes = AtomicLong()
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private data class CompressedCapture(val cipher: javax.crypto.Cipher)
    private val compressed = mutableMapOf<String, CompressedCapture>()
    private val compressedReserve = mutableMapOf<String, Long>()
    private val pendingKey: javax.crypto.SecretKey by lazy {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("jianyu_diagnostic_pending", null) as? javax.crypto.SecretKey) ?: run {
            javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(android.security.keystore.KeyGenParameterSpec.Builder("jianyu_diagnostic_pending",
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_CTR, android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
    @Volatile private var app: Context? = null
    @Volatile private var database: DiagnosticDatabase? = null
    private lateinit var directory: File

    @Synchronized fun init(context: Context) {
        if (app != null) return
        val contextApp = context.applicationContext
        app = contextApp
        directory = File(contextApp.jianyuNoBackupFilesDir, "api_diagnostics").apply { mkdirs() }
        val prefs = contextApp.jianyuPreferences("telemetry_settings", Context.MODE_PRIVATE)
        _level.value = runCatching { TelemetryLevel.valueOf(prefs.getString("telemetry_level", null).orEmpty()) }.getOrDefault(TelemetryLevel.METADATA_ONLY)
        _capacity.value = prefs.getLong("capacity_bytes", 500L shl 20)
        enqueue(force = true) {
            val db = Room.databaseBuilder(contextApp, DiagnosticDatabase::class.java, "api_diagnostics.db").build()
            database = db
            try { migrate(contextApp, db) } catch (error: Exception) { _storageError.value = "旧日志迁移失败，原数据已保留：${error.javaClass.simpleName}" }
            db.exchanges().pending().forEach { row ->
                val event = json.decodeFromString<TelemetryEvent>(row.metadata)
                if (event.captureState == "RECORDING") {
                    val recovery = if (event.responseEncoding.equals("gzip", true) && event.responseFile != null) sanitizeGzip(requireNotNull(event.responseFile), emptyList()) else null
                    put(event.copy(captureState = "INCOMPLETE", captureError = recovery ?: "采集因进程结束而中断；已保留收到的内容",
                        recoveryResult = if (event.recoveryResult == "WAITING") "FAILED" else event.recoveryResult,
                        failureType = if (event.recoveryResult == "WAITING") "PROCESS_INTERRUPTED" else event.failureType))
                }
            }
            refreshUsage()
            _revision.value++
            scope.launch { db.exchanges().observe().collect { rows ->
                _events.value = rows.mapNotNull { runCatching { json.decodeFromString<TelemetryEvent>(it.metadata) }.getOrNull() }
            } }
            scope.launch { db.exchanges().observeCount().collect { _totalCount.value = it } }
        }
    }
    fun currentLevel() = _level.value
    fun setLevel(context: Context, requested: TelemetryLevel): Boolean {
        init(context)
        val saved = context.jianyuPreferences("telemetry_settings", Context.MODE_PRIVATE).edit()
            .putString("telemetry_level", requested.name).remove("content_debug_expires_at").commit()
        if (saved) _level.value = requested
        return saved
    }
    fun setCapacity(bytes: Long) {
        require(bytes in listOf(100L shl 20, 500L shl 20, 1L shl 30))
        _capacity.value = bytes
        app?.jianyuPreferences("telemetry_settings", Context.MODE_PRIVATE)?.edit()?.putLong("capacity_bytes", bytes)?.apply()
        enqueue(force = true) { refreshUsage(); if (_usedBytes.value < bytes) _storageError.value = null }
    }
    fun captureEnabled() = app != null && _level.value != TelemetryLevel.OFF && _storageError.value?.startsWith("采集暂停") != true
    fun currentGeneration() = generation.get()
    fun nextAttempt(operationId: String) = attempts.getOrPut(operationId) { java.util.concurrent.atomic.AtomicInteger() }.getAndIncrement()
    fun isActive(id: String, version: Long) = version == generation.get() && id !in deleted && operations[id]?.let { it !in blockedOperations } == true
    fun payloadName(id: String, kind: String) = "$id.$kind"
    fun streamEvent(id: String, version: Long, data: String, secrets: List<String>) {
        if (currentLevel() != TelemetryLevel.CONTENT_DEBUG) return
        val line = org.json.JSONObject().put("receivedAt", System.currentTimeMillis())
            .put("data", DiagnosticCredentials.body(data, secrets)).toString() + "\n"
        append(id, version, payloadName(id, "events"), line.toByteArray())
    }
    fun keyName(id: String?, provider: String?): String? = runCatching {
        val context = app ?: return@runCatching null
        val owner = if (provider == "DeepSeek") com.elio.jianyu.network.AiProvider.DEEPSEEK else com.elio.jianyu.network.AiProvider.GEMINI
        com.elio.jianyu.network.AiManager.keys(context, owner).summaries.value.firstOrNull { it.id == id }?.displayName
    }.getOrNull()
    @Synchronized fun record(event: TelemetryEvent): Long {
        val version = generation.get()
        if (!captureEnabled() || event.operationId in blockedOperations) return version
        operations[event.id] = event.operationId
        enqueue { if (isActive(event.id, version)) put(event) }
        return version
    }
    private fun put(event: TelemetryEvent) {
        val safe = event.copy(endpoint = DiagnosticCredentials.url(event.endpoint), keyName = event.keyName ?: keyName(event.keyId, event.provider), errorMessage = event.errorMessage?.let { DiagnosticCredentials.text(it) })
        database?.exchanges()?.put(DiagnosticRow(safe.id, safe.timestamp, safe.operationId, json.encodeToString(safe)))
        _revision.value++
    }
    fun enqueue(force: Boolean = false, action: () -> Unit): Boolean {
        if (!force && !captureEnabled()) return false
        val task = Runnable { try { action() } catch (error: Exception) { _storageError.value = "采集暂停：${DiagnosticCredentials.text(error.toString())}" } }
        return try {
            writer.execute(task)
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (force) {
                // 清理、导出和终态不能因正文队列满而丢失，也不能阻塞主线程。
                scope.launch { writer.queue.put(task) }
                true
            } else { _storageError.value = "采集暂停：写入队列积压，业务请求继续执行"; false }
        }
    }
    fun append(id: String, version: Long, name: String, bytes: ByteArray, finish: Boolean = false, secrets: List<String> = emptyList()): Boolean {
        if (!isActive(id, version) || _level.value != TelemetryLevel.CONTENT_DEBUG) return false
        if (pendingBytes.addAndGet(bytes.size.toLong()) > (4L shl 20)) {
            pendingBytes.addAndGet(-bytes.size.toLong()); _storageError.value = "采集暂停：待写入正文超过 4 MiB"; return false
        }
        val accepted = enqueue(force = finish) {
            try {
                if (isActive(id, version)) {
                    val additional = bytes.size.toLong() * if (name.endsWith(".gzip")) 2 else 1
                    if (_usedBytes.value + compressedReserve.values.sum() + additional >= _capacity.value - (1L shl 20)) _storageError.value = "采集暂停：空间或压缩处理预留空间已满，请手动清理或提高上限"
                    else {
                        if (name.endsWith(".gzip")) {
                            val state = compressed.getOrPut(name) {
                                val key = pendingKey
                                val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
                                val nonce = cipher.iv
                                val credentials = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
                                val protectedSecrets = credentials.iv + credentials.doFinal(json.encodeToString(secrets).toByteArray())
                                java.io.DataOutputStream(File(directory, "$name.pending").outputStream()).use {
                                    it.writeInt(protectedSecrets.size); it.write(protectedSecrets); it.write(nonce)
                                }
                                CompressedCapture(cipher)
                            }
                            val encrypted = state.cipher.update(bytes)
                            if (encrypted != null) File(directory, "$name.pending").appendBytes(encrypted)
                            compressedReserve[name] = (compressedReserve[name] ?: 0L) + bytes.size
                        } else File(directory, name).appendBytes(bytes)
                        _usedBytes.value += bytes.size
                        val now = System.currentTimeMillis()
                        if (now - _payloadRevision.value >= 250) _payloadRevision.value = now
                    }
                }
            } finally { pendingBytes.addAndGet(-bytes.size.toLong()) }
        }
        if (!accepted) pendingBytes.addAndGet(-bytes.size.toLong())
        return accepted
    }
    fun complete(event: TelemetryEvent, version: Long, secrets: List<String> = emptyList()) { enqueue(force = true) {
        if (isActive(event.id, version)) {
            val gzipError = if (event.responseEncoding.equals("gzip", true) && event.responseFile != null) {
                try { sanitizeGzip(requireNotNull(event.responseFile), secrets) } finally { compressedReserve.remove(event.responseFile) }
            } else null
            val previous = database?.exchanges()?.get(event.id)?.let { json.decodeFromString<TelemetryEvent>(it.metadata) }
            val logicalSuccess = previous?.recoveryResult == "SUCCESS" && event.statusCode?.let { it in 200..299 } == true
            val merged = event.copy(model = event.model ?: previous?.model, phase = previous?.phase ?: event.phase,
                hasThoughtStep = previous?.hasThoughtStep ?: event.hasThoughtStep,
                inputTokens = previous?.inputTokens ?: event.inputTokens, outputTokens = previous?.outputTokens ?: event.outputTokens,
                failureType = if (logicalSuccess) null else previous?.failureType?.takeIf { it in setOf("STREAM_FAILED", "STREAM_STALLED", "CANCELLED", "TIMEOUT") } ?: event.failureType,
                errorMessage = if (logicalSuccess) null else previous?.errorMessage ?: event.errorMessage,
                recoveryResult = previous?.recoveryResult ?: event.recoveryResult,
                captureState = if (gzipError != null) "INCOMPLETE" else event.captureState,
                captureError = gzipError ?: event.captureError)
            put(if (_storageError.value?.startsWith("采集暂停") != true) merged else merged.copy(captureState = "INCOMPLETE", captureError = _storageError.value))
            refreshUsage()
        }
    } }
    private fun sanitizeGzip(name: String, secrets: List<String>): String? {
        val original = File(directory, name)
        val pending = File(directory, "$name.pending")
        val state = compressed.remove(name)
        if (!pending.exists()) return null
        if (state != null) pending.appendBytes(state.cipher.doFinal())
        var headerSize = 0L
        lateinit var nonce: ByteArray
        val recoveredSecrets: List<String>
        try {
            java.io.DataInputStream(pending.inputStream()).use {
                val length = it.readInt(); require(length in 28..65536) { "加密记录头无效" }
                val protectedSecrets = ByteArray(length); it.readFully(protectedSecrets)
                nonce = ByteArray(16); it.readFully(nonce); headerSize = 4L + length + 16
                val credentials = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(javax.crypto.Cipher.DECRYPT_MODE, pendingKey, javax.crypto.spec.GCMParameterSpec(128, protectedSecrets.copyOfRange(0, 12)))
                }
                recoveredSecrets = json.decodeFromString(String(credentials.doFinal(protectedSecrets.copyOfRange(12, length)), Charsets.UTF_8))
            }
        } catch (error: Exception) { return "无法恢复加密的压缩记录：${error.javaClass.simpleName}" }
        fun decrypted(): java.io.InputStream {
            val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, pendingKey, javax.crypto.spec.IvParameterSpec(nonce)) }
            val input = java.io.DataInputStream(pending.inputStream()); input.skipBytes(headerSize.toInt())
            return javax.crypto.CipherInputStream(input, cipher)
        }
        val temporary = File(directory, "$name.masked")
        val masker = DiagnosticByteMasker(secrets + recoveredSecrets)
        var decodingError: String? = null
        try {
            java.util.zip.GZIPOutputStream(temporary.outputStream()).use { output ->
                try {
                    GZIPInputStream(decrypted()).use { source ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = source.read(buffer); if (n < 0) break; output.write(masker.accept(buffer.copyOf(n))) }
                    }
                } catch (error: java.io.IOException) { decodingError = "压缩响应未收齐：${error.javaClass.simpleName}；已保留可解码内容" }
                finally { output.write(masker.accept(byteArrayOf(), true)) }
            }
            if (masker.replaced || decodingError != null) check(temporary.renameTo(original)) { "压缩响应认证处理失败" }
            else {
                // 未出现凭据时保存原始压缩字节，避免重新压缩改变服务器实际响应。
                decrypted().use { source -> original.outputStream().use { source.copyTo(it) } }
                temporary.delete()
            }
            return decodingError
        } catch (error: Exception) {
            temporary.delete()
            return "压缩响应记录不完整：${error.javaClass.simpleName}"
        } finally { pending.delete() }
    }
    fun update(id: String, transform: (TelemetryEvent) -> TelemetryEvent) {
        val version = generation.get()
        enqueue(force = true) { if (isActive(id, version)) database?.exchanges()?.get(id)?.let { put(transform(json.decodeFromString(it.metadata))) } }
    }
    fun delete(ids: Set<String>) {
        deleted.addAll(ids)
        enqueue(force = true) {
            ids.toList().chunked(500).forEach { database?.exchanges()?.delete(it) }
            compressed.keys.filter { it.substringBefore('.') in ids }.forEach { compressed.remove(it) }
            compressedReserve.keys.filter { it.substringBefore('.') in ids }.forEach { compressedReserve.remove(it) }
            directory.listFiles()?.filter { it.name.substringBefore('.') in ids }?.forEach { it.delete() }
            refreshUsage(); if (_usedBytes.value < _capacity.value) _storageError.value = null
            _revision.value++
        }
    }
    fun deleteOperation(operationId: String) {
        blockedOperations.add(operationId)
        enqueue(force = true) {
            val ids = database?.exchanges()?.operation(operationId).orEmpty().map { it.id }.toSet() + operations.filterValues { it == operationId }.keys
            delete(ids)
        }
    }
    @Synchronized fun clearAllTelemetry(context: Context): Boolean {
        init(context); blockedOperations.addAll(operations.values); generation.incrementAndGet()
        operations.clear()
        return enqueue(force = true) {
            database?.clearAllTables(); directory.listFiles()?.forEach { it.delete() }
            context.jianyuPreferences("roundtable_telemetry_prefs", Context.MODE_PRIVATE).edit().remove("telemetry_events_json").commit()
            context.jianyuPreferences("gemini_api_key_prefs", Context.MODE_PRIVATE).edit().remove("telemetry_api_logs_json").commit()
            compressed.clear()
            compressedReserve.clear()
            attempts.clear()
            _storageError.value = null; refreshUsage()
            _revision.value++
        }
    }
    private fun refreshUsage() {
        val context = app ?: return
        _usedBytes.value = (directory.listFiles()?.sumOf { it.length() } ?: 0L) +
            listOf("api_diagnostics.db", "api_diagnostics.db-wal", "api_diagnostics.db-shm").sumOf { context.getDatabasePath(it).length() }
        if (_usedBytes.value >= _capacity.value) _storageError.value = "采集暂停：空间已满"
    }
    private fun source(name: String) = File(directory, File(name).name).inputStream().let { if (name.endsWith(".gzip")) GZIPInputStream(it) else it }
    suspend fun payloadSize(name: String): Long = withContext(Dispatchers.IO) { File(directory, File(name).name).length() }
    /** 流式格式化全文到文件，避免为大 JSON 分配一个巨大的字符串。原文件不变。 */
    suspend fun formattedFile(name: String): String = withContext(Dispatchers.IO) {
        val done = java.util.concurrent.CompletableFuture<String>()
        val accepted = enqueue(force = true) {
            val formatted = File(directory, "${File(name).name}.pretty")
            val temporary = File(directory, "${File(name).name}.pretty.pending")
            var outputToClose: java.io.OutputStream? = null
            try {
                val original = File(directory, File(name).name)
                if (!formatted.exists() || formatted.lastModified() < original.lastModified()) {
                    val remaining = (_capacity.value - _usedBytes.value).coerceAtLeast(0)
                    var written = 0L
                    val limited = object : java.io.FilterOutputStream(temporary.outputStream()) {
                        override fun write(b: Int) { check(written++ < remaining) { "格式化全文超过剩余日志空间" }; out.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) { check(written + len <= remaining) { "格式化全文超过剩余日志空间" }; out.write(b, off, len); written += len }
                    }
                    outputToClose = limited
                    android.util.JsonReader(source(name).bufferedReader()).use { reader ->
                        android.util.JsonWriter(limited.bufferedWriter()).use { writer ->
                            writer.setIndent("  ")
                            fun copyValue() {
                                when (reader.peek()) {
                                    android.util.JsonToken.BEGIN_OBJECT -> { reader.beginObject(); writer.beginObject(); while (reader.hasNext()) { writer.name(reader.nextName()); copyValue() }; reader.endObject(); writer.endObject() }
                                    android.util.JsonToken.BEGIN_ARRAY -> { reader.beginArray(); writer.beginArray(); while (reader.hasNext()) copyValue(); reader.endArray(); writer.endArray() }
                                    android.util.JsonToken.STRING -> writer.value(reader.nextString())
                                    android.util.JsonToken.NUMBER -> writer.value(java.math.BigDecimal(reader.nextString()))
                                    android.util.JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
                                    android.util.JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
                                    else -> error("不是完整合法 JSON，可查看原始文本")
                                }
                            }
                            copyValue()
                            check(reader.peek() == android.util.JsonToken.END_DOCUMENT) { "JSON 后还有额外内容，可查看原始文本" }
                        }
                    }
                    check(temporary.renameTo(formatted)) { "无法保存格式化文件" }
                    refreshUsage()
                }
                done.complete(formatted.name)
            } catch (error: Exception) { temporary.delete(); done.completeExceptionally(error) }
            finally { outputToClose?.close() }
        }
        check(accepted) { "格式化队列已满" }; done.get()
    }
    suspend fun readPage(name: String?, offset: Long = 0, size: Int = 16_384): Triple<String, Boolean, Long> = withContext(Dispatchers.IO) {
        if (name == null || !File(directory, File(name).name).exists()) return@withContext Triple("", false, offset)
        source(name).bufferedReader().use {
            var remaining = offset
            while (remaining > 0) { val skipped = it.skip(remaining); if (skipped == 0L) break; remaining -= skipped }
            val buffer = CharArray(size); val count = it.read(buffer)
            if (count <= 0) Triple("", false, offset) else {
                var text = String(buffer, 0, count)
                if (text.last().isHighSurrogate()) { val low = it.read(); if (low != -1) text += low.toChar() }
                Triple(text, it.read() != -1, offset + text.length)
            }
        }
    }
    private fun bodyMatches(event: TelemetryEvent, query: String): Boolean =
        listOfNotNull(event.requestFile, event.responseFile).any { name -> runCatching { source(name).bufferedReader().use { reader ->
            val buffer = CharArray(8192); var tail = ""; var found = false
            while (!found) { val n = reader.read(buffer); if (n < 0) break; val text = tail + String(buffer, 0, n); found = text.contains(query, true); tail = text.takeLast(query.length) }
            found
        } }.getOrDefault(false) }
    private fun matches(e: TelemetryEvent, filter: DiagnosticFilter): Boolean {
        if (e.id in deleted || e.operationId in blockedOperations) return false
        val success = e.failureType == null && e.errorMessage == null && (e.statusCode?.let { it in 200..299 } ?: (e.recoveryResult == "SUCCESS"))
        val metadata = listOf(e.endpoint, e.model, e.provider, e.keyId, e.keyName, e.purpose, e.phase, e.phaseDetails, e.sessionId, e.characterId, e.errorMessage).joinToString(" ")
        return (filter.provider == "全部" || e.provider == filter.provider) && e.model.orEmpty().contains(filter.model, true) &&
            "${e.keyId} ${e.keyName}".contains(filter.key, true) && "${e.purpose} ${e.phase}".contains(filter.purpose, true) &&
            (filter.session.isEmpty() || e.sessionId?.toString() == filter.session) &&
            (filter.outcome == "全部" || filter.outcome == "成功" && success || filter.outcome == "失败" && !success && e.captureState != "RECORDING" && e.recoveryResult != "WAITING") &&
            (filter.hours == 0 || e.timestamp >= System.currentTimeMillis() - filter.hours * 3600_000L) &&
            (filter.query.isEmpty() || metadata.contains(filter.query, true) || bodyMatches(e, filter.query))
    }
    suspend fun search(filter: DiagnosticFilter, limit: Int): Pair<List<TelemetryEvent>, Boolean> = withContext(Dispatchers.IO) {
        val dao = database?.exchanges() ?: return@withContext emptyList<TelemetryEvent>() to false
        val found = mutableListOf<TelemetryEvent>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = dao.page(200, offset)
            if (rows.isEmpty()) break
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                val event = json.decodeFromString<TelemetryEvent>(row.metadata)
                if (matches(event, filter)) found.add(event)
                if (found.size > limit) return@withContext found.take(limit) to true
            }
            offset += rows.size
        }
        found to false
    }
    suspend fun wholeText(name: String?): String = withContext(Dispatchers.IO) {
        if (name == null || !File(directory, File(name).name).exists()) "" else source(name).bufferedReader().use { reader ->
            val output = StringBuilder(); val buffer = CharArray(8192)
            while (true) { val n = reader.read(buffer); if (n < 0) break; check(output.length + n <= 200_000) { "正文超过剪贴板容量，请使用 ZIP 导出完整内容" }; output.append(buffer, 0, n) }
            output.toString()
        }
    }
    fun eventJson(event: TelemetryEvent) = json.encodeToString(event)
    suspend fun export(context: Context, uri: Uri, selected: List<TelemetryEvent>?, operationId: String? = null, filter: DiagnosticFilter? = null) = withContext(Dispatchers.IO) {
        val done = java.util.concurrent.CompletableFuture<Unit>()
        val accepted = enqueue(force = true) { try {
            val dao = checkNotNull(database).exchanges()
            val ids = selected?.map { it.id }?.toSet()
            fun visit(action: (TelemetryEvent) -> Unit) {
                fun accept(row: DiagnosticRow) {
                    val event = json.decodeFromString<TelemetryEvent>(row.metadata)
                    if (matches(event, filter ?: DiagnosticFilter())) action(event)
                }
                when {
                    ids != null -> ids.forEach { dao.get(it)?.let(::accept) }
                    operationId != null -> dao.operation(operationId).forEach(::accept)
                    else -> { var offset = 0; while (true) { val rows = dao.page(200, offset); if (rows.isEmpty()) break; rows.forEach(::accept); offset += rows.size } }
                }
            }
            context.contentResolver.openOutputStream(uri)!!.use { output -> ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write("{\"schemaVersion\":2,\"records\":[".toByteArray())
                var first = true
                visit { event ->
                    if (!first) zip.write(','.code); first = false
                    val snapshot = if (event.captureState == "RECORDING") event.copy(captureState = "INCOMPLETE", captureError = "导出快照生成时请求尚未结束") else event
                    zip.write(json.encodeToString(snapshot).toByteArray())
                }
                zip.write("]}".toByteArray()); zip.closeEntry()
                visit { event -> listOfNotNull(event.requestFile, event.responseFile, event.streamEventsFile).distinct().forEach { name ->
                    val file = File(directory, File(name).name)
                    if (file.exists()) { zip.putNextEntry(ZipEntry("payloads/$name")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                } }
            } }
            done.complete(Unit)
        } catch (error: Exception) { done.completeExceptionally(error) } }
        check(accepted) { "导出队列已满" }; done.get()
    }
    private fun migrate(context: Context, db: DiagnosticDatabase) {
        val prefs = context.jianyuPreferences("roundtable_telemetry_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("telemetry_events_json", null)
        val keyPrefs = context.jianyuPreferences("gemini_api_key_prefs", Context.MODE_PRIVATE)
        val legacyRaw = keyPrefs.getString("telemetry_api_logs_json", null)
        val old = raw?.let { json.decodeFromString<List<TelemetryEvent>>(it) }.orEmpty() +
            legacyRaw?.let { json.decodeFromString<List<com.elio.jianyu.network.ApiLog>>(it) }.orEmpty().map { log ->
                val id = java.util.UUID.nameUUIDFromBytes("${log.requestTime}:${log.keyId}:${log.model}".toByteArray()).toString()
                TelemetryEvent(id, log.requestTime, log.responseTime - log.requestTime, "legacy", model = log.model, keyId = log.keyId,
                    statusCode = log.statusCode.takeIf { it > 0 }, failureType = log.errorMessage?.let { "LEGACY_ERROR" }, errorMessage = log.errorMessage,
                    requestPreview = log.prompt, responsePreview = log.responseText)
            }
        db.runInTransaction { old.forEach { event ->
            fun body(text: String?, kind: String): String? = text?.let { payloadName(event.id, kind).also { name -> File(directory, name).writeText(DiagnosticCredentials.text(it)) } }
            put(event.copy(requestFile = body(event.requestPreview, "request"), responseFile = body(event.responsePreview, "response"), requestPreview = null, responsePreview = null, historical = true, expiresAt = null))
        } }
        prefs.edit().remove("telemetry_events_json").remove("telemetry_schema_version").commit()
        keyPrefs.edit().remove("telemetry_api_logs_json").commit()
    }
}
