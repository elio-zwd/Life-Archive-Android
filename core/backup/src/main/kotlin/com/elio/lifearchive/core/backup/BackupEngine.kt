package com.elio.lifearchive.core.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 回调均在 StorageGate 内执行；只能操作本域数据，不可再调用公开 Repository。 */
interface BackupParticipant {
    val id: String
    suspend fun snapshot(directory: File)
    suspend fun validate(directory: File)
    suspend fun restore(directory: File)
}

data class PreparedRestore(val token: String, val participants: Set<String>)

/** 异常或取消表示调用未完整结束；提交标记可能已落盘，须初始化并读取最终状态。 */
class BackupEngine(
    private val root: File,
    private val gate: StorageGate,
    participants: List<BackupParticipant>,
) {
    private val participants = participants.sortedBy { it.id }
    private val json = Json
    private val transaction = File(root, "transaction.json")
    internal var fault: (String) -> Unit = {}

    @Serializable private data class Manifest(val format: Int = 1, val participants: List<String>)
    @Serializable private data class Transaction(val token: String, val committed: Boolean = false)

    init {
        require(this.participants.isNotEmpty())
        require(this.participants.map { it.id }.distinct().size == this.participants.size)
        require(this.participants.all { it.id.matches(Regex("[a-z][a-z0-9-]*")) })
        gate.installRecovery { recoverLocked() }
    }

    suspend fun initialize() = gate.access { Unit }

    suspend fun export(output: OutputStream) = gate.access {
        val staging = File(root, "export-${UUID.randomUUID()}")
        try {
            snapshot(staging)
            // 关闭 ZIP 会关闭传入流；与 SAF 的 use 生命周期一致。
            ZipOutputStream(output).use { zip ->
                staging.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(staging).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } finally { staging.deleteRecursively() }
    }

    suspend fun prepare(input: InputStream): PreparedRestore = gate.access {
        val token = UUID.randomUUID().toString()
        val staging = staged(token)
        check(staging.mkdirs())
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "备份不接受目录条目" }
                    val name = entry.name
                    require(validEntry(name) && names.add(name.lowercase(java.util.Locale.ROOT))) { "非法或重复 ZIP 路径" }
                    require(names.size <= 20000) { "备份条目过多" }
                    val file = File(staging, name)
                    require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 2L * 1024 * 1024 * 1024) { "备份解压超过 2 GiB" }
                            out.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            validate(staging)
            PreparedRestore(token, participants.map { it.id }.toSet())
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    suspend fun discard(prepared: PreparedRestore) = gate.access { staged(prepared.token).deleteRecursively(); Unit }

    /** 仅在 UI 已明确确认替换后调用；token 不接受任意外部路径。 */
    suspend fun commit(prepared: PreparedRestore) = gate.access {
        val source = staged(prepared.token)
        validate(source)
        val rollback = File(root, "rollback")
        if (rollback.exists()) check(rollback.deleteRecursively())
        snapshot(rollback)
        atomicWrite(transaction, json.encodeToString(Transaction(prepared.token)).toByteArray())
        gate.requireRecovery()
        try {
            participants.forEach { it.restore(File(source, it.id)) }
            atomicWrite(transaction, json.encodeToString(Transaction(prepared.token, true)).toByteArray())
            fault("committed")
            recoverLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try { recoverLocked() } catch (rollbackError: Exception) { e.addSuppressed(rollbackError) }
            throw e
        }
    }

    private suspend fun snapshot(directory: File) {
        check(directory.isDirectory || directory.mkdirs())
        participants.forEach { p ->
            val dir = File(directory, p.id)
            check(dir.mkdirs())
            p.snapshot(dir)
        }
        atomicWrite(File(directory, "manifest.json"), json.encodeToString(Manifest(participants = participants.map { it.id })).toByteArray())
        validate(directory)
    }

    private suspend fun validate(directory: File) {
        val manifestFile = File(directory, "manifest.json")
        require(manifestFile.length() in 1..65536)
        val manifest = json.decodeFromString<Manifest>(manifestFile.readText())
        require(manifest.format == 1 && manifest.participants == participants.map { it.id }) { "备份格式或参与者不匹配" }
        require(directory.list()?.toSet() == (manifest.participants + "manifest.json").toSet()) { "备份包含未知数据域" }
        participants.forEach { it.validate(File(directory, it.id)) }
    }

    private suspend fun recoverLocked() {
        if (!transaction.exists()) return
        val tx = json.decodeFromString<Transaction>(transaction.readText())
        val rollback = File(root, "rollback")
        if (!tx.committed) {
            validate(rollback)
            participants.forEach { it.restore(File(rollback, it.id)) }
            atomicWrite(transaction, json.encodeToString(tx.copy(committed = true)).toByteArray())
        }
        // committed 记录先持久化，清理中断后只继续清理，不再次回滚。
        check(staged(tx.token).let { !it.exists() || it.deleteRecursively() })
        check(!rollback.exists() || rollback.deleteRecursively())
        check(transaction.delete())
    }

    private fun staged(token: String): File {
        require(UUID.fromString(token).toString() == token)
        return File(root, "prepared-$token")
    }

    private fun validEntry(name: String): Boolean = name.length <= 512 && name.split('/').all {
        it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') && !it.endsWith(' ') &&
            it.none { c -> c.code < 32 || c in "\\:*?\"<>|" } &&
            !Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$").matches(it)
    }
}
