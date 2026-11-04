package com.elio.lifearchive.data.sync

import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.document.isSyncPath
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.domain.sync.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class SyncRuntime(root: File, private val gate: StorageGate, private val notes: NotesSyncRepository, credentials: DeviceCredentials = KeystoreCredentials(File(root, "device.bin"))) : SyncRepository {
    private val persistence = SyncPersistence(root, gate, credentials)
    val backupParticipant = persistence.participant
    private val mutable = MutableStateFlow(SyncState())
    override val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val staging = File(root, "staging")
    private fun <T> NoteResult<T>.value(): T = when (this) {
        is NoteResult.Success -> value
        is NoteResult.Committed -> value
        else -> error(failureMessage())
    }
    private suspend fun run(action: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        mutable.value = state.value.copy(busy = true)
        try { withContext(Dispatchers.IO) { action() } }
        catch (e: CancellationException) { mutable.value = state.value.copy(message = "同步已停止，已完成项保留；下次重新核对"); throw e }
        catch (e: Exception) { mutable.value = state.value.copy(message = "操作未完成：${e.message}") }
        finally { mutable.value = state.value.copy(busy = false); mutex.unlock() }
    }
    private suspend fun configuration(): Pair<SyncConfig, String?> = gate.access {
        val config = persistence.configLocked()
        val token = persistence.credentials.read()
        mutable.value = state.value.copy(address = config.address, paired = token != null)
        config to token
    }
    override suspend fun connect(address: String, code: String) = run {
        val normalized = normalizeSyncAddress(address)
        val response = SyncHttpClient(normalized).pair(code.trim())
        require(response.protocol == 1 && UUID.fromString(response.libraryId).toString() == response.libraryId && response.token.matches(Regex("[a-f0-9]{64}"))) { "电脑同步协议不兼容" }
        gate.access { persistence.credentials.write(response.token); persistence.saveConfigLocked(SyncConfig(address = normalized, libraryId = response.libraryId)) }
        mutable.value = state.value.copy(address = normalized, paired = true, conflicts = emptyList(), message = "配对完成，可手动同步")
    }
    override suspend fun testConnection() = run {
        val (config, token) = configuration(); requireNotNull(token) { "请先配对" }
        val peer = SyncHttpClient(config.address, token).identity()
        require(peer.protocol == 1 && peer.libraryId == config.libraryId) { "电脑资料库已改变，请重新配对" }
        mutable.value = state.value.copy(message = "连接正常，可以手动同步；连接测试不修改文档")
    }
    override suspend fun synchronize(choices: Map<String, SyncChoice>) = run {
        val displayed = state.value.conflicts.associateBy { it.key }
        val (config, token) = configuration(); requireNotNull(token) { "请先配对" }
        val generation = persistence.generation
        val client = SyncHttpClient(config.address, token)
        val computer = client.manifest()
        require(computer.protocol == 1 && computer.libraryId == config.libraryId) { "电脑资料库已改变，请重新配对" }
        require(computer.files.all { isSyncPath(it.path) && it.size in 0..2L * 1024 * 1024 * 1024 } && computer.files.map { it.key }.distinct().size == computer.files.size) { "电脑清单无效" }
        val phone = notes.inventory().value()
        val base = gate.access { persistence.baseLocked().toMutableMap() }
        val local = phone.files.associateBy { it.key }.toMutableMap(); val remote = computer.files.associateBy { it.key }.toMutableMap()
        val issues = (phone.issues + computer.issues).toMutableList(); val conflicts = mutableListOf<SyncConflict>()
        val blocked = mutableSetOf<String>()
        // 相同路径却有不同 ID 时必须先选择；不能先把其中一份当普通新增覆盖。
        for (p in phone.files.filter { it.kind != "folder" }) {
            val c = computer.files.firstOrNull { it.kind != "folder" && it.key != p.key && it.path.equals(p.path, true) } ?: continue
            val key = "path:${p.path.lowercase(java.util.Locale.ROOT)}"
            val conflict = SyncConflict(key, p, c)
            val choice = choices[key].takeIf { displayed[key] == conflict }
            when (choice) {
                SyncChoice.PHONE -> { client.apply(SyncChange(UUID.randomUUID().toString(), c, null), null); remote.remove(c.key); base.remove(c.key) }
                SyncChoice.COMPUTER -> { notes.apply(p, null).value(); local.remove(p.key); base.remove(p.key) }
                else -> { conflicts += conflict; blocked += p.key; blocked += c.key }
            }
        }
        val keys = (base.keys + local.keys + remote.keys).distinct().sortedWith(compareBy<String> { key ->
            val e = local[key] ?: remote[key] ?: base.getValue(key)
            if (e.kind == "folder") { if (local[key] == null || remote[key] == null) { if (base[key] != null) 4 else 0 } else 0 } else if (local[key] == null || remote[key] == null) { if (base[key] != null) 3 else if (e.kind == "attachment") 1 else 2 } else if (e.kind == "attachment") 1 else 2
        }.thenBy { key -> val e = local[key] ?: remote[key] ?: base.getValue(key); if (e.kind == "folder" && base[key] != null) -e.path.count { it == '/' } else e.path.count { it == '/' } })
        mutable.value = state.value.copy(message = "正在核对并同步…", completed = 0, total = keys.size, conflicts = conflicts.toList(), issues = issues.toList(), uploaded = 0, downloaded = 0, transferredBytes = 0)
        check(staging.isDirectory || staging.mkdirs())
        for ((index, key) in keys.withIndex()) {
            currentCoroutineContext().ensureActive()
            check(generation == persistence.generation) { "资料库已恢复或连接已变化，本轮同步停止" }
            val p = local[key]; val c = remote[key]; val prior = base[key]
            if (key in blocked) continue
            val candidates = listOfNotNull(p, c, prior)
            if (issues.any { issue -> issue.key == key || candidates.any { it.path == issue.path || it.path.startsWith(issue.path + "/") } }) continue
            var way = direction(prior, p, c)
            if (way == SyncDirection.CONFLICT) {
                val conflict = SyncConflict(key, p, c)
                val choice = choices[key].takeIf { displayed[key] == conflict }
                way = when (choice) { SyncChoice.PHONE -> SyncDirection.TO_COMPUTER; SyncChoice.COMPUTER -> SyncDirection.TO_PHONE; else -> { conflicts += conflict; continue } }
            }
            val desired = if (way == SyncDirection.TO_PHONE) c else p
            val temp = File(staging, UUID.randomUUID().toString())
            try {
                when (way) {
                    SyncDirection.TO_COMPUTER -> {
                        if (desired != null && desired.kind != "folder") temp.outputStream().use { notes.copy(desired, it).value() }
                        client.apply(SyncChange(UUID.randomUUID().toString(), c, desired), temp.takeIf { it.isFile })
                        mutable.value = state.value.copy(uploaded = state.value.uploaded + 1)
                    }
                    SyncDirection.TO_PHONE -> {
                        if (desired != null && desired.kind != "folder") client.download(desired, temp)
                        temp.takeIf { it.isFile }?.inputStream().use { notes.apply(p, desired, it).value() }
                        mutable.value = state.value.copy(downloaded = state.value.downloaded + 1)
                    }
                    else -> Unit
                }
                // 手机仍是计划版本时才前移基线；之后的新编辑留给下一轮核对。
                require(way == SyncDirection.SAME || notes.matches(key, desired).value()) { "同步期间手机资料又发生变化，请重试" }
                gate.access {
                    check(generation == persistence.generation)
                    if (desired == null) base.remove(key) else base[key] = desired
                    persistence.saveBaseLocked(base)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SyncHttpException) {
                if (e.status != 409) throw e
                conflicts += SyncConflict(key, p, c); issues += SyncIssue((p ?: c ?: prior)!!.path, "目标路径或版本冲突，请核对后重试", key)
            } catch (e: IllegalStateException) { issues += SyncIssue((p ?: c ?: prior)!!.path, e.message ?: "同步未完成", key) }
            finally { temp.delete() }
            mutable.value = state.value.copy(completed = index + 1, transferredBytes = client.transferred)
        }
        mutable.value = state.value.copy(conflicts = conflicts, issues = issues.toList(), message = "本轮完成：上传 ${state.value.uploaded} 项，下载 ${state.value.downloaded} 项；待处理冲突 ${conflicts.size} 项，提示 ${issues.size} 项", completed = keys.size, transferredBytes = client.transferred)
        mutable.value = state.value.copy(trash = notes.attachmentTrash().value())
    }
    override suspend fun refreshTrash() = run { configuration(); mutable.value = state.value.copy(trash = notes.attachmentTrash().value()) }
    override suspend fun restoreAttachment(token: String) = run { notes.restoreAttachment(token).value(); mutable.value = state.value.copy(trash = notes.attachmentTrash().value(), message = "附件已恢复，下次同步会重新核对") }
}
