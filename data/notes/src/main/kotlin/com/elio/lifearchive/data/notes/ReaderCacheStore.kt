package com.elio.lifearchive.data.notes

import com.elio.lifearchive.core.backup.atomicWrite
import com.elio.lifearchive.domain.notes.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 只保存可丢弃的阅读派生物；不占用正文 StorageGate，也不注册备份参与者。 */
class ReaderCacheStore(
    private val root: File,
    private val memoryCapacity: Long = 32L * 1024 * 1024,
    private val diskCapacity: Long = 256L * 1024 * 1024,
    private val entryCapacity: Int = 8 * 1024 * 1024,
) : ReaderCacheRepository {
    private val mutex = Mutex()
    private val memory = LinkedHashMap<String, ByteArray>(16, .75f, true)
    private var memoryBytes = 0L
    private var namespace: String? = null
    private var hits = 0L
    private var misses = 0L
    internal var fault: (String) -> Unit = {}

    private fun initialize(): ReaderCacheToken {
        val marker = File(root, "namespace")
        if (namespace == null) root.listFiles().orEmpty().filter { it.name.endsWith(".pending") }.forEach { it.delete() }
        val stored = runCatching { marker.readText().trim().takeIf { UUID.fromString(it).toString() == it } }.getOrNull()
        if (stored != namespace || namespace == null) {
            memory.clear(); memoryBytes = 0
            namespace = if (namespace == null && stored != null) stored else UUID.randomUUID().toString()
            if (stored != namespace) atomicWrite(marker, namespace!!.toByteArray())
            trim()
        }
        return ReaderCacheToken(namespace!!)
    }
    private fun filename(key: ReaderArtifactKey): String {
        require(Regex("[a-f0-9]{64}").matches(key.digest)) { "非法阅读缓存标识" }
        return "${key.kind.name.lowercase()}-${key.digest}.bin"
    }
    private fun files() = root.listFiles().orEmpty().filter { it.isFile && it.name != "namespace" }
    private fun remember(name: String, bytes: ByteArray) {
        memory.remove(name)?.let { memoryBytes -= it.size }
        if (bytes.size <= memoryCapacity) { memory[name] = bytes; memoryBytes += bytes.size }
        while (memoryBytes > memoryCapacity) {
            val first = memory.entries.iterator(); val entry = first.next(); memoryBytes -= entry.value.size; first.remove()
        }
    }
    private fun trim() {
        val entries = files().sortedBy { it.lastModified() }
        var bytes = entries.sumOf { it.length() }
        for (file in entries) {
            if (bytes <= diskCapacity) break
            val size = file.length(); if (file.delete()) bytes -= size
        }
    }
    override suspend fun token(): ReaderCacheToken = withContext(Dispatchers.IO) { mutex.withLock { initialize() } }
    override suspend fun get(token: ReaderCacheToken, key: ReaderArtifactKey): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (initialize() != token) { misses++; return@withLock null }
                val name = filename(key)
                val file = File(root, name)
                val bytes = memory[name] ?: run {
                    if (!file.isFile || file.length() > entryCapacity.toLong() + 1024) { misses++; return@withLock null }
                    DataInputStream(file.inputStream().buffered()).use { input ->
                        check(input.readInt() == 1 && input.readUTF() == token.namespace && input.readUTF() == name)
                        val count = input.readInt(); check(count in 0..entryCapacity)
                        val hash = ByteArray(32).also(input::readFully)
                        val data = ByteArray(count).also(input::readFully)
                        check(input.read() == -1 && hash.contentEquals(MessageDigest.getInstance("SHA-256").digest(data)))
                        remember(name, data); data
                    }
                }
                file.setLastModified(System.currentTimeMillis()); hits++
                bytes.toString(Charsets.UTF_8)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                runCatching { val name = filename(key); memory.remove(name)?.let { memoryBytes -= it.size }; File(root, name).delete() }
                misses++; null
            }
        }
    }
    override suspend fun put(token: ReaderCacheToken, key: ReaderArtifactKey, value: String): Boolean = withContext(Dispatchers.IO) {
        // 代次在调用方开始计算前取得，等待锁后再核对，不能在提交时获取新代次。
        if (value.length > entryCapacity) return@withContext false
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > entryCapacity) return@withContext false
        mutex.withLock {
            try {
                if (initialize() != token) return@withLock false
                val name = filename(key)
                val encoded = ByteArrayOutputStream().use { buffer ->
                    DataOutputStream(buffer).use { output ->
                        output.writeInt(1); output.writeUTF(token.namespace); output.writeUTF(name)
                        output.writeInt(bytes.size); output.write(MessageDigest.getInstance("SHA-256").digest(bytes)); output.write(bytes)
                    }; buffer.toByteArray()
                }
                if (encoded.size > diskCapacity) return@withLock false
                fault("write")
                atomicWrite(File(root, name), encoded)
                remember(name, bytes); trim(); true
            } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        }
    }
    override suspend fun usage(): ReaderCacheUsage = withContext(Dispatchers.IO) { mutex.withLock {
        initialize(); ReaderCacheUsage(memoryBytes, files().sumOf { it.length() }, hits, misses)
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        memory.clear(); memoryBytes = 0; namespace = UUID.randomUUID().toString()
        // 先持久化新代次；即使旧文件清理失败，旧任务和旧文件也不能成为有效命中。
        atomicWrite(File(root, "namespace"), namespace!!.toByteArray())
        val removed = root.listFiles().orEmpty().filter { it.name != "namespace" }.map { it.deleteRecursively() }.all { it }
        check(removed) { "部分阅读缓存未能清理" }
    } }
}
