package com.elio.lifearchive.core.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 单个库共享一把门锁。恢复失败时不开放后续读写；参与者回调不可重入。 */
class StorageGate {
    private val mutex = Mutex()
    private var recovery: (suspend () -> Unit)? = null
    private var recovered = false

    fun installRecovery(block: suspend () -> Unit) {
        check(recovery == null) { "同一存储门锁只能装配一个恢复协调器" }
        recovery = block
        recovered = false
    }

    suspend fun <T> access(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!recovered) {
                recovery?.invoke()
                recovered = true
            }
            block()
        }
    }

    // 调用者已持有门锁；下一次访问必须先完成中断恢复。
    internal fun requireRecovery() { recovered = false }
}

/** 同目录原子替换。失败时保留旧目标；不退化为先删目标再重命名。 */
fun atomicWrite(target: File, bytes: ByteArray) {
    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
    val temp = File(target.parentFile, ".${target.name}.pending")
    FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
