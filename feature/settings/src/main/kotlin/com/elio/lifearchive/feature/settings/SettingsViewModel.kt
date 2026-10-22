package com.elio.lifearchive.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.backup.BackupEngine
import com.elio.lifearchive.core.backup.PreparedRestore
import com.elio.lifearchive.core.backup.RestoreResult
import com.elio.lifearchive.core.preferences.*
import com.elio.lifearchive.domain.notes.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(val busy: Boolean = false, val message: String? = null, val cacheBytes: Long = 0, val restore: PreparedRestore? = null, val importing: PreparedImport? = null)
class SettingsViewModel(private val preferences: PreferencesRepository, private val backup: BackupEngine, private val notes: NotesRepository, private val library: LibraryRepository, private val cache: RemoteCacheRepository) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState())
    val state = mutable.asStateFlow()
    init { refreshCache() }
    fun refreshCache() { viewModelScope.launch { val bytes = cache.size(); mutable.value = state.value.copy(cacheBytes = bytes) } }
    private fun run(action: suspend () -> String) {
        if (state.value.busy) return
        mutable.value = state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { val message = action(); mutable.value = state.value.copy(message = message) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(message = "操作未完成：${e.message}") }
            finally { mutable.value = state.value.copy(busy = false) }
        }
    }
    fun theme(value: ThemeMode) = run { preferences.setTheme(value); "主题已保存" }
    fun font(value: Float) = run { preferences.setFontScale(value); "字号已保存" }
    fun remote(value: RemoteLoading) = run { preferences.setRemoteLoading(value); "远程加载设置已保存" }
    fun clearCache() = run { cache.clear(); mutable.value = state.value.copy(cacheBytes = 0); "已清理远程资源及网页副本，笔记和附件不受影响" }
    fun exportBackup(open: () -> OutputStream?) = run { withContext(Dispatchers.IO) { (open() ?: error("无法创建备份文件")).use { backup.export(it) } }; "完整备份导出成功" }
    fun prepareRestore(open: () -> InputStream?) = run {
        val prepared = withContext(Dispatchers.IO) { (open() ?: error("无法读取备份")).use { backup.prepare(it) } }
        mutable.value = state.value.copy(restore = prepared); "备份已验证，确认后替换现有资料"
    }
    fun cancelRestore() = run { state.value.restore?.let { backup.discard(it) }; mutable.value = state.value.copy(restore = null); "已取消恢复" }
    fun restore(beforeRestore: suspend () -> Boolean = { true }, onRestored: () -> Unit) = run {
        val prepared = state.value.restore ?: error("请先选择备份")
        check(beforeRestore()) { "当前编辑内容尚未安全保存，未替换资料" }
        try {
            when (val result = backup.commit(prepared)) {
                is RestoreResult.Committed -> {
                    val initialized = notes.initialize()
                    mutable.value = state.value.copy(restore = null, importing = null)
                    when {
                        initialized !is NoteResult.Success -> "资料已替换，资料库初始化未完成，请重启后检查：$initialized"
                        result.cleanupPending -> "资料已恢复，临时文件待清理"
                        else -> "完整恢复成功"
                    }
                }
                is RestoreResult.RolledBack -> { mutable.value = state.value.copy(restore = null); "恢复失败，现有资料已回滚：${result.reason}" }
                is RestoreResult.RecoveryRequired -> error("资料库需完成恢复，请重启后重试：${result.reason}")
            }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { notes.initialize(); throw e }
        finally {
            // 已结束的编辑会话不能在失败回滚后继续复用；所有提交结果均换代导航。
            onRestored()
        }
    }
    fun prepareImport(entries: List<ImportEntry>) = run {
        val result = library.prepareImport(entries, "导入-${UUID.randomUUID().toString().take(8)}")
        check(result is NoteResult.Success) { result.failureMessage() }; mutable.value = state.value.copy(importing = result.value)
        "资料已暂存，确认后导入"
    }
    fun cancelImport() = run { state.value.importing?.let { library.discardImport(it) }; mutable.value = state.value.copy(importing = null); "已取消导入，资料库未改变" }
    fun commitImport() = run { val prepared = state.value.importing ?: error("请先选择资料"); val result = library.commitImport(prepared); check(result is NoteResult.Success) { result.failureMessage() }; mutable.value = state.value.copy(importing = null); "导入完成：${prepared.folder}" }
    fun exportLibrary(open: () -> OutputStream?, ids: Set<NoteId>? = null) = run { val result = withContext(Dispatchers.IO) { (open() ?: error("无法创建导出文件")).use { library.export(it, ids) } }; check(result is NoteResult.Success) { result.failureMessage() }; "便携 ZIP 导出成功" }
    fun exportMarkdown(id: NoteId, open: () -> OutputStream?) = run {
        val read = notes.read(id); check(read is NoteResult.Success) { read.toString() }
        withContext(Dispatchers.IO) { (open() ?: error("无法创建文件")).use { it.write(read.value.document.utf8()) } }; "Markdown 原文导出成功"
    }
}
