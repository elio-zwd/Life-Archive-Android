package com.elio.jianyu.backup.complete

import android.content.Context
import android.net.Uri
import com.elio.jianyu.JianyuAppRuntimeProvider
import com.elio.jianyu.backup.*
import com.elio.jianyu.network.AiManager
import com.elio.jianyu.network.AiProvider
import com.elio.jianyu.preferences.AppPreferences
import com.elio.jianyu.runtime.DatabaseMaintenanceOutcome
import com.elio.jianyu.storage.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object CompleteTransferRecovery {
    private val resumed = ConcurrentHashMap.newKeySet<String>()
    fun target(context: Context): CompleteTarget {
        val app = context.applicationContext
        return CompleteTarget(app.getDatabasePath("jianyu_roundtable_database"), File(app.filesDir, "jianyu"),
            File(app.noBackupFilesDir, "jianyu/full-transfer"), File(app.noBackupFilesDir, "jianyu/content-epoch")) {
            app.jianyuPreferences(it, Context.MODE_PRIVATE)
        }
    }
    // 见域任务在维护入口已结束；恢复只替换该域资料，不能删除公共凭据。
    fun resetCredentials(context: Context): Boolean = true
    fun recover(context: Context) {
        val destination = target(context)
        CompleteReplacement(destination).recover { check(resetCredentials(context)) { "见域凭据清理尚未完成，恢复记录已保留" } }
        // 每个进程首次公开见域前清理上次进程遗留的暂存，不删除运行中操作的目录。
        if (resumed.add(destination.operations.absolutePath)) for (file in destination.operations.listFiles().orEmpty()) {
            val stage = file.name.removeSuffix(".jyfull")
            val supported = stage.substringBefore('-') in setOf("preview", "export", "verify") &&
                runCatching { UUID.fromString(stage.substringAfter('-')).toString() == stage.substringAfter('-') }.getOrDefault(false)
            if (supported || file.name == "active") {
                require(destination.operations.canonicalFile == destination.operations.absoluteFile)
                Files.walk(file.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            }
        }
    }
}

/** 同一见域的数据操作串行，运行在进程作用域；关闭旧页面不会中断正在提交的恢复。 */
internal class AndroidJianyuCompleteBackup private constructor(context: Context) : JianyuCompleteBackup {
    private val app = context.applicationContext
    private val target = CompleteTransferRecovery.target(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableOperation = MutableStateFlow<JianyuCompleteOperation>(JianyuCompleteOperation.Idle)
    override val operation = mutableOperation.asStateFlow()
    private val mutablePreview = MutableStateFlow<JianyuCompletePreview?>(null)
    override val preview = mutablePreview.asStateFlow()
    private data class Prepared(val directory: File, val manifest: CompleteArchiveCodec.Manifest, val fingerprint: String)
    private var prepared: Prepared? = null
    private var verifiedExportFingerprint: String? = null

    override suspend fun inspect(uri: String, password: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableOperation.value = JianyuCompleteOperation.Busy("正在解密并核对完整见域备份…")
            var directory: File? = null
            try {
                discardPrepared()
                val staging = staging("preview").also { directory = it }
                val manifest = runInterruptible {
                    requireNotNull(app.contentResolver.openInputStream(Uri.parse(uri))) { "无法读取备份文件" }.use { input -> CompleteArchiveCodec.read(input, password, staging) }
                }
                CompleteDatabase.initialize(app)
                CompleteDatabase.inspect(File(staging, "database.sqlite"), manifest.rows)
                CompleteDatabase.requireIdle(File(staging, "database.sqlite"))
                CompleteDatabase.verifyFiles(staging, false)
                CompleteSettings.validate(readSettings(staging))
                val current = currentState()
                val token = staging.name.removePrefix("preview-")
                prepared = Prepared(staging, manifest, current.second)
                mutablePreview.value = JianyuCompletePreview(token, manifest.sourcePackage, manifest.sourceVersion, manifest.createdAt,
                    manifest.rows, manifest.files.count { it.path.startsWith("content/") }, manifest.files.filter { it.path.startsWith("content/") }.sumOf { it.size },
                    manifest.notes + "不包含 API Key 和设备密钥，恢复后须重新配置。" + "恢复会替换见域设置并清空未发送输入；Life 笔记不在覆盖范围内。", !current.first,
                    current.first || verifiedExportFingerprint == current.second)
                mutableOperation.value = JianyuCompleteOperation.Idle
            } catch (error: CancellationException) {
                runCatching { directory?.let(::deleteOwned) }
                mutableOperation.value = JianyuCompleteOperation.Idle; throw error
            } catch (error: Throwable) {
                runCatching { directory?.let(::deleteOwned) }
                if (!currentCoroutineContext().isActive) {
                    mutableOperation.value = JianyuCompleteOperation.Idle
                    currentCoroutineContext().ensureActive()
                }
                mutableOperation.value = JianyuCompleteOperation.Failed(readFailure(error))
            }
        }
    }

    override suspend fun dismissPreview() = withContext(Dispatchers.IO) { mutex.withLock { discardPrepared() } }

    override fun startExport(uri: String, password: String) = start("正在创建完整见域备份…") {
        CompleteDatabase.initialize(app)
        mutableOperation.value = JianyuCompleteOperation.Busy("正在核对当前见域能否创建完整备份…")
        currentState() // 预检活动任务，普通导出不应为了取得快照而中断用户的生成。
        mutableOperation.value = JianyuCompleteOperation.Busy("正在暂停见域操作以取得一致快照…")
        val staging = staging("export")
        val archive = File(target.operations, "${staging.name}.jyfull")
        var capturedFingerprint: String? = null
        var originalRows = emptyMap<String, Long>()
        try {
            val outcome = JianyuAppRuntimeProvider.withDatabaseClosed(app,
                beforeClose = { database ->
                    mutableOperation.value = JianyuCompleteOperation.Busy("正在保存见域的一致快照…")
                    CompleteDatabase.requireIdle(target.database)
                    originalRows = CompleteDatabase.inspect(target.database)
                    checkpoint(database)
                },
                whileClosed = { databaseFile ->
                    checkNoWal(databaseFile)
                    val settings = CompleteSettings.capture(target.preferences)
                    capturedFingerprint = CompleteDatabase.fingerprint(databaseFile, settings, target.content)
                    CompleteDatabase.copySynced(databaseFile, File(staging, "database.sqlite"))
                    copyContent(staging)
                    CompleteSettings.write(File(staging, "settings.json"), settings)
                },
                afterReopen = { CompleteDatabase.inspect(target.database, originalRows) },
            )
            requireMaintenanceSuccess(outcome)
            mutableOperation.value = JianyuCompleteOperation.Busy("正在加密并验证完整见域备份…")
            val notices = CompleteDatabase.prepareExport(staging) { app.ownedAudioFile(it) }
            val rows = CompleteDatabase.inspect(File(staging, "database.sqlite"), originalRows)
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val manifest = CompleteArchiveCodec.describe(staging, app.packageName, info.versionName ?: "未知", rows,
                notices + "此包只包含见域资料，不包含公共 AI 配置、Key 或伙伴资料；恢复时保留本机公共 AI 设置。")
            FileOutputStream(archive).use { output -> CompleteArchiveCodec.write(staging, manifest, password, output); output.fd.sync() }
            verifyArchive(archive.inputStream(), password, manifest)
            mutableOperation.value = JianyuCompleteOperation.Busy("备份已验证，正在保存到所选文件…")
            val destination = Uri.parse(uri)
            requireNotNull(app.contentResolver.openOutputStream(destination, "wt")) { "无法写入所选备份文件" }.use { output -> archive.inputStream().use { it.copyTo(output, 64 * 1024) }; output.flush() }
            requireNotNull(app.contentResolver.openInputStream(destination)) { "无法回读已导出的文件，不能确认备份完成" }.use { verifyArchive(it, password, manifest) }
            verifiedExportFingerprint = capturedFingerprint
            prepared?.let { previous ->
                val current = currentState()
                if (current.second == capturedFingerprint) {
                    prepared = previous.copy(fingerprint = current.second)
                    mutablePreview.value = mutablePreview.value?.copy(targetHasData = !current.first, targetBackupReady = true)
                }
            }
            "完整见域备份已保存并回读验证；不包含 Life 笔记、API Key 或设备密钥。"
        } finally { deleteOwned(staging); if (archive.exists()) check(archive.delete()) }
    }

    override fun startRestore(token: String, replaceExisting: Boolean) = start("正在恢复见域，Life 仍可继续使用…") {
        val incoming = checkNotNull(prepared) { "请先选择并预览完整见域备份" }
        require(incoming.directory.name == "preview-${UUID.fromString(token)}") { "恢复预览已失效，请重新选择文件" }
        val replacement = CompleteReplacement(target)
        var applied = false
        var clearedCredentials = false
        try {
            val current = currentState()
            check(current.second == incoming.fingerprint) { "预览后见域内容发生变化，请重新预览并备份当前内容" }
            if (!current.first) {
                require(replaceExisting) { "见域已有数据，须明确选择替换" }
                check(verifiedExportFingerprint == current.second) { "请先在此页面创建当前见域的完整备份，再执行替换" }
            }
            CompleteDatabase.prepareImport(incoming.directory, target.content)
            val outcome = JianyuAppRuntimeProvider.withDatabaseClosed(app,
                beforeClose = { database ->
                    CompleteDatabase.requireIdle(target.database)
                    check(CompleteDatabase.fingerprint(target.database, CompleteSettings.capture(target.preferences), target.content) == incoming.fingerprint) { "提交前见域内容已变化，已停止恢复" }
                    checkpoint(database)
                },
                whileClosed = {
                    try { replacement.begin(incoming.directory, incoming.manifest); applied = true }
                    catch (error: Throwable) { replacement.recover(); throw error }
                },
                afterReopen = {
                    CompleteDatabase.inspect(target.database, incoming.manifest.rows)
                    verifyRestoredFiles(incoming.manifest)
                    check(CompleteSettings.capture(target.preferences) == CompleteSettings.restoredSnapshot(readSettings(incoming.directory))) { "恢复后的见域设置核对失败" }
                    AppPreferences.initialize(app)
                    AiManager.reloadConfiguration(app)
                    replacement.finalizeCommit {
                        clearedCredentials = CompleteTransferRecovery.resetCredentials(app)
                        check(clearedCredentials) { "见域内容已提交，但凭据清理未完成；恢复记录已保留，请重试打开见域" }
                    }
                },
            )
            if (outcome is DatabaseMaintenanceOutcome.Failure) {
                val committed = replacement.isCommitted()
                if (replacement.hasPending()) {
                    replacement.recover { check(CompleteTransferRecovery.resetCredentials(app)) { "见域内容已提交，但凭据清理仍未完成；Life 可继续使用" } }
                    AppPreferences.initialize(app)
                    AiManager.reloadConfiguration(app)
                }
                if (applied) check(JianyuAppRuntimeProvider.retryOpen(app)) { "恢复失败，见域暂时不能打开；回滚资料和日志已保留，Life 可继续使用" }
                if (committed) {
                    verifiedExportFingerprint = null
                    return@start "见域已恢复并完成收尾；公共 AI 配置和 Key 保留。Life 笔记未被替换。"
                }
                requireMaintenanceSuccess(outcome)
            }
            // 凭据不从包内恢复，公共配置也不随单域资料回滚；旧见域任务已在维护入口结束。
            verifiedExportFingerprint = null
            if (clearedCredentials) "见域已恢复并逐项核对；公共 AI 配置和 Key 保留。Life 笔记未被替换。"
            else "见域内容已恢复，收尾未完成，请重试。Life 笔记未被替换。"
        } finally {
            discardPrepared()
        }
    }

    /** 旧有限导出／设备快照也使用同一操作锁，并独立于维护时会被释放的 UI 任务。 */
    suspend fun <T> legacy(block: suspend () -> T): T = scope.async {
        mutex.withLock {
            mutableOperation.value = JianyuCompleteOperation.Busy("正在处理见域历史备份…")
            try { block().also { mutableOperation.value = JianyuCompleteOperation.Finished("见域历史备份操作已完成；其覆盖范围仍按旧格式标注。") } }
            catch (error: Throwable) { mutableOperation.value = JianyuCompleteOperation.Failed(readFailure(error)); throw error }
        }
    }.await()

    suspend fun clearOwned(action: suspend () -> Pair<String, Boolean>): String = scope.async {
        mutex.withLock {
            mutableOperation.value = JianyuCompleteOperation.Busy("正在清除见域本地数据…")
            try {
                CompleteTransferRecovery.recover(app)
                discardPrepared(); verifiedExportFingerprint = null
                // 在任何删除前先持久化内容换代，进程中断也不能恢复失效的旧导航。
                CompleteReplacement(target).rotateEpoch()
                val (message, databaseCleared) = action()
                if (databaseCleared) {
                    val outcome = JianyuAppRuntimeProvider.withDatabaseClosed(app,
                        beforeClose = { checkpoint(it) }, whileClosed = {}, afterReopen = {
                            AppPreferences.initialize(app); AiManager.reloadConfiguration(app)
                        })
                    requireMaintenanceSuccess(outcome)
                }
                mutableOperation.value = if (message == "本地数据已清除") JianyuCompleteOperation.Finished(message) else JianyuCompleteOperation.Failed(message)
                message
            } catch (error: Throwable) {
                readFailure(error)
                val message = "见域清理尚未完成，请重新打开后核对；Life 笔记不在清理范围内。"
                mutableOperation.value = JianyuCompleteOperation.Failed(message)
                message
            }
        }
    }.await()

    private fun start(message: String, action: suspend () -> String) {
        if (!mutex.tryLock()) return
        mutableOperation.value = JianyuCompleteOperation.Busy(message)
        scope.launch {
            try { mutableOperation.value = JianyuCompleteOperation.Finished(action()) }
            catch (error: Throwable) { mutableOperation.value = JianyuCompleteOperation.Failed(readFailure(error)) }
            finally { mutex.unlock() }
        }
    }

    private suspend fun currentState(): Pair<Boolean, String> = JianyuAppRuntimeProvider.withRuntime(app) { runtime ->
        // 页面就绪不代表延迟打开的 Room 已建库；完整备份不能依赖首个 UI 查询的时序。
        runtime.database.openHelper.writableDatabase
        CompleteDatabase.initialize(app)
        CompleteDatabase.requireIdle(target.database)
        CompleteDatabase.inspect(target.database)
        CompleteDatabase.isEmpty(target.database) to CompleteDatabase.fingerprint(target.database, CompleteSettings.capture(target.preferences), target.content)
    }

    private fun verifyArchive(input: java.io.InputStream, password: String, expected: CompleteArchiveCodec.Manifest) {
        val verification = staging("verify")
        try {
            input.use { check(CompleteArchiveCodec.read(it, password, verification) == expected) { "备份回读清单不一致" } }
            CompleteDatabase.inspect(File(verification, "database.sqlite"), expected.rows)
            CompleteDatabase.verifyFiles(verification, false)
            CompleteSettings.validate(readSettings(verification))
        } finally { deleteOwned(verification) }
    }

    private fun verifyRestoredFiles(manifest: CompleteArchiveCodec.Manifest) {
        for (item in manifest.files.filter { it.path.startsWith("content/") }) {
            val file = File(target.content, item.path.removePrefix("content/"))
            require(file.canonicalFile.toPath().startsWith(target.content.canonicalFile.toPath()) && file.isFile && file.length() == item.size && CompleteArchiveCodec.sha256(file) == item.sha256) { "恢复后的见域附件核对失败" }
        }
    }

    private fun copyContent(staging: File) {
        if (!target.content.exists()) return
        for (file in target.content.walkTopDown().onEnter { require(!Files.isSymbolicLink(it.toPath())); true }.filter(File::isFile)) {
            val relative = file.relativeTo(target.content).invariantSeparatorsPath
            require(file.canonicalFile.toPath().startsWith(target.content.canonicalFile.toPath()) && !Files.isSymbolicLink(file.toPath())) { "见域文件包含不支持的链接" }
            CompleteDatabase.copySynced(file, CompleteArchiveCodec.ownedFile(staging, "content/$relative"))
        }
    }

    private fun readSettings(directory: File): String {
        val file = File(directory, "settings.json")
        require(file.isFile && file.length() <= 8 * 1024 * 1024) { "见域设置文件过大或缺失" }
        return file.readText(Charsets.UTF_8)
    }

    private fun checkpoint(database: com.elio.jianyu.data.RoundtableDatabase) {
        check(database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 0 }) { "见域数据库尚未完成 checkpoint" }
    }
    private fun checkNoWal(file: File) { check(!File(file.absolutePath + "-wal").exists() || File(file.absolutePath + "-wal").length() == 0L) { "见域 WAL 尚未安全关闭" } }
    private fun requireMaintenanceSuccess(outcome: DatabaseMaintenanceOutcome<*>) {
        if (outcome is DatabaseMaintenanceOutcome.Failure) throw outcome.cause
    }
    private fun staging(prefix: String): File = File(target.operations, "$prefix-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    private fun discardPrepared() { prepared?.directory?.let(::deleteOwned); prepared = null; mutablePreview.value = null }
    private fun deleteOwned(file: File) {
        if (!file.exists()) return
        require(file.canonicalFile.toPath().startsWith(target.operations.canonicalFile.toPath()) && file.canonicalFile != target.operations.canonicalFile)
        Files.walk(file.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
    private fun readFailure(error: Throwable): String {
        // 诊断只记录异常类型、固定错误码和代码栈，不能记录正文、密码或 URI。
        android.util.Log.e("JianyuCompleteBackup", buildString {
            append(mutableOperation.value).append('\n')
            for (cause in generateSequence(error) { it.cause }.take(4)) {
                append(cause.javaClass.name).append('\n')
                append(cause.stackTrace.take(8).joinToString("\n")).append('\n')
                append(cause.stackTrace.filter { it.className.startsWith("com.elio.") }.take(8).joinToString("\n")).append('\n')
            }
        })
        return when (error) {
            is IllegalArgumentException, is IllegalStateException -> error.message?.take(600) ?: "完整备份校验失败，已停止操作。"
            else -> "完整备份操作未完成；请核对密码、文件完整性和可用空间。恢复日志若存在会保留，Life 笔记不在操作范围内。"
        }
    }

    companion object {
        private val instances = ConcurrentHashMap<String, AndroidJianyuCompleteBackup>()
        fun forContext(context: Context): AndroidJianyuCompleteBackup = instances.computeIfAbsent(context.applicationContext.getDatabasePath("jianyu_roundtable_database").absolutePath) { AndroidJianyuCompleteBackup(context.applicationContext) }
    }
}
