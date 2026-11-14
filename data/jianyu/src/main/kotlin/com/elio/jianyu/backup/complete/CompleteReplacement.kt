package com.elio.jianyu.backup.complete

import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

/** 固定见域目标路径由装配层提供；日志不能传入任意目标或跨域路径。 */
internal data class CompleteTarget(
    val database: File,
    val content: File,
    val operations: File,
    val epoch: File,
    val preferences: (String) -> SharedPreferences,
)

/** 所有调用须在见域运行消费者释放、数据库关闭后执行。重开验证前不能 finalizeCommit。 */
internal class CompleteReplacement(private val target: CompleteTarget, private val fault: (String) -> Unit = {}) {
    private val root = target.operations.canonicalFile
    private val active = File(root, "active")
    private val journal = AtomicFile(File(root, "replacement.json"))

    init {
        require(target.database.canonicalFile != target.content.canonicalFile)
        require(!target.content.canonicalFile.toPath().startsWith(root.toPath()) && !root.toPath().startsWith(target.content.canonicalFile.toPath()))
    }

    fun begin(staging: File, manifest: CompleteArchiveCodec.Manifest) {
        check(!exists(journal)) { "存在未完成的见域恢复记录" }
        require(manifest.rows.keys == CompleteDatabase.tables)
        require(staging.canonicalFile.toPath().startsWith(root.toPath()) && staging.canonicalFile != active.canonicalFile)
        check(target.database.isFile)
        root.mkdirs()
        if (active.exists()) deleteOwned(active)
        check(active.mkdirs())
        val oldDatabase = File(active, "old.sqlite")
        CompleteDatabase.copySynced(target.database, oldDatabase)
        CompleteSettings.write(File(active, "old-settings.json"), CompleteSettings.capture(target.preferences))
        val oldEpoch = AtomicFile(target.epoch).let { file -> if (exists(file)) file.openRead().bufferedReader().use { it.readText() }.also(UUID::fromString) else null }
        val record = JSONObject().put("version", 1).put("state", "prepared")
            .put("oldDatabaseHash", CompleteArchiveCodec.sha256(oldDatabase))
            .put("oldSettingsHash", CompleteArchiveCodec.sha256(File(active, "old-settings.json")))
            .put("hadContent", target.content.exists()).put("oldEpoch", oldEpoch ?: JSONObject.NULL)
        write(journal, record.toString())
        fault("after-journal")
        val oldContent = File(active, "old-content")
        if (target.content.exists()) move(target.content, oldContent)
        fault("after-old-content")
        replaceDatabase(File(staging, "database.sqlite"))
        fault("after-database")
        val newContent = File(staging, "content").apply { mkdirs() }
        move(newContent, target.content)
        fault("after-content")
        CompleteSettings.apply(File(staging, "settings.json").readText(Charsets.UTF_8), target.preferences)
        fault("after-settings")
        rotateEpoch()
        fault("after-epoch")
        write(journal, record.put("state", "applied").toString())
        fault("after-applied")
    }

    fun finalizeCommit(afterCommitted: () -> Unit = {}) {
        val record = readJournal() ?: error("缺少见域恢复记录")
        require(record.getString("state") == "applied")
        write(journal, record.put("state", "committed").toString())
        afterCommitted()
        write(journal, record.put("state", "finalized").toString())
        // 已确认提交后，清理失败只保留日志，不能把成功恢复误报为失败再反向覆盖。
        runCatching { cleanup() }
    }

    /** 初始化必须在公开 Repository 前调用；prepared/applied 统一回滚，committed 只补清理。 */
    fun recover(afterCommitted: () -> Unit = {}): Boolean {
        val record = readJournal() ?: return false
        require(record.getInt("version") == 1)
        when (record.getString("state")) {
            "committed" -> { afterCommitted(); write(journal, record.put("state", "finalized").toString()); cleanup(); return true }
            "finalized" -> { cleanup(); return true }
            "rolled-back" -> { cleanup(); return true }
            "prepared", "applied" -> Unit
            else -> error("未知的见域恢复阶段，已停止打开数据")
        }
        val oldDatabase = File(active, "old.sqlite")
        check(oldDatabase.isFile && CompleteArchiveCodec.sha256(oldDatabase) == record.getString("oldDatabaseHash")) { "见域回滚数据库损坏，已保留所有文件并停止打开数据" }
        val settingsFile = File(active, "old-settings.json")
        check(settingsFile.isFile && settingsFile.length() <= 8 * 1024 * 1024 && CompleteArchiveCodec.sha256(settingsFile) == record.getString("oldSettingsHash"))
        val settings = settingsFile.readText(Charsets.UTF_8)
        CompleteSettings.validate(settings)
        replaceDatabase(oldDatabase)
        val oldContent = File(active, "old-content")
        if (oldContent.exists()) {
            if (target.content.exists()) deleteContent()
            move(oldContent, target.content)
        } else if (!record.getBoolean("hadContent") && target.content.exists()) deleteContent()
        CompleteSettings.apply(settings, target.preferences)
        if (record.isNull("oldEpoch")) AtomicFile(target.epoch).delete()
        else write(AtomicFile(target.epoch), record.getString("oldEpoch").also(UUID::fromString))
        write(journal, record.put("state", "rolled-back").toString())
        cleanup()
        return true
    }

    fun hasPending(): Boolean = exists(journal)
    fun isCommitted(): Boolean = readJournal()?.optString("state") in setOf("committed", "finalized")
    fun rotateEpoch() { write(AtomicFile(target.epoch), UUID.randomUUID().toString()) }

    private fun replaceDatabase(source: File) {
        val temporary = File(target.database.parentFile, "${target.database.name}.jyfull-replace")
        val expectedHash = CompleteArchiveCodec.sha256(source)
        CompleteDatabase.copySynced(source, temporary)
        check(CompleteArchiveCodec.sha256(temporary) == expectedHash) { "见域数据库替换副本回读校验失败" }
        // 只处理这一个数据库的伴随文件；不能枚举或清理另一个业务的数据库目录。
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val companion = File(target.database.absolutePath + suffix)
            check(!companion.exists() || companion.delete()) { "无法移除旧见域数据库伴随文件" }
        }
        move(temporary, target.database, replace = true)
    }

    private fun readJournal(): JSONObject? = if (!exists(journal)) null else {
        require(journal.baseFile.length() <= 16384)
        JSONObject(journal.openRead().bufferedReader().use { it.readText() })
    }

    private fun cleanup() {
        if (active.exists()) deleteOwned(active)
        journal.delete()
        check(!journal.baseFile.exists())
    }

    private fun deleteOwned(file: File) {
        require(file.canonicalFile.toPath().startsWith(root.toPath()) && file.canonicalFile != root)
        deleteTree(file)
    }

    private fun deleteContent() {
        require(target.content.canonicalFile == target.content.absoluteFile && target.content.canonicalFile != root)
        deleteTree(target.content)
    }

    private fun move(source: File, destination: File, replace: Boolean = false) {
        destination.parentFile!!.mkdirs()
        val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) else arrayOf(StandardCopyOption.ATOMIC_MOVE)
        Files.move(source.toPath(), destination.toPath(), *options)
    }

    private fun write(file: AtomicFile, raw: String) {
        file.baseFile.parentFile!!.mkdirs()
        // Android 与 Windows Robolectric 都要求同目录原子替换；File.renameTo 在 Windows
        // 覆盖已有目标时可能只返回 false，不能让世代或日志停留在旧值。
        val temporary = File(file.baseFile.absolutePath + ".full-new")
        require(!Files.isSymbolicLink(temporary.toPath()))
        FileOutputStream(temporary).use { output -> output.write(raw.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        move(temporary, file.baseFile, replace = true)
        val legacyBackup = File(file.baseFile.absolutePath + ".bak")
        check(!legacyBackup.exists() || legacyBackup.delete())
    }
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.absolutePath + ".bak").exists()
    private fun deleteTree(file: File) {
        // 不跟随目录中的符号链接；只能删除链接本身，不能沿链接清理另一业务。
        Files.walk(file.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
