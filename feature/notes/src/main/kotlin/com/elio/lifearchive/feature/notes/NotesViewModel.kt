package com.elio.lifearchive.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import java.util.UUID

data class NotesUiState(val notes: List<NoteSummary> = emptyList(), val loading: Boolean = true, val creating: Boolean = false, val error: String? = null, val query: NotesQuery = NotesQuery(), val listing: LibraryListing? = null, val progress: LibraryProgress? = null, val updating: Boolean = false, val thumbnailEpoch: Long = 0)

class NotesViewModel(private val repository: NotesRepository, private val library: LibraryRepository? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(NotesUiState())
    val state = mutableState.asStateFlow()
    private var queryJob: Job? = null
    private var observationQueryJob: Job? = null
    private var request = 0L
    private val thumbnailLoader = library?.let(::HomeThumbnailLoader)
    init {
        if (library != null) viewModelScope.launch { library.progress.collect { value -> mutableState.value = state.value.copy(progress = value) } }
        viewModelScope.launch {
            try {
                var firstSnapshot = true
                repository.observeNotes().collect {
                    if (library == null) mutableState.value = mutableState.value.copy(notes = it, loading = false)
                    else {
                        // 在新查询排队前令旧查询失效，避免旧回执抢先更新界面。
                        request++
                        queryJob?.cancel()
                        observationQueryJob?.cancel()
                        mutableState.value = mutableState.value.copy(updating = true)
                        // 存储通知也可能是附件或目录变化，不能只比较笔记摘要。
                        // 首次快照尚无旧资料；筛选和排序查询本身不触发换代。
                        if (!firstSnapshot) {
                            thumbnailLoader?.invalidate()
                            mutableState.value = mutableState.value.copy(thumbnailEpoch = mutableState.value.thumbnailEpoch + 1)
                        }
                        firstSnapshot = false
                        // 查询可能等待 IO；继续收集后续变更，及时隔离旧缩略图回执。
                        observationQueryJob = viewModelScope.launch { refresh() }
                    }
                }
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = mutableState.value.copy(loading = false, error = e.message ?: "无法读取笔记列表") }
        }
    }

    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }
    fun query(value: NotesQuery) {
        if (value == state.value.query) return
        request++
        observationQueryJob?.cancel()
        mutableState.value = mutableState.value.copy(query = value, updating = true)
        queryJob?.cancel(); queryJob = viewModelScope.launch { delay(200); refresh() }
    }
    suspend fun refresh() {
        val engine = library ?: return
        val captured = state.value.query
        val sequence = ++request
        mutableState.value = state.value.copy(updating = true)
        try {
            val result = engine.query(captured)
            if (sequence != request || captured != state.value.query) return
            mutableState.value = when (result) {
                is NoteResult.Success -> state.value.copy(notes = result.value.notes.map { it.summary }, listing = result.value, error = null, loading = false, updating = false)
                else -> state.value.copy(error = "查询失败：${result.failureMessage()}", loading = false, updating = false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            if (sequence == request) mutableState.value = state.value.copy(error = "查询失败：${e.message}", loading = false, updating = false)
        }
    }
    fun operation(action: suspend (LibraryRepository) -> NoteResult<Unit>) {
        val engine = library ?: return
        viewModelScope.launch { val result = action(engine); if (result !is NoteResult.Success) mutableState.value = mutableState.value.copy(error = result.failureMessage()); else refresh() }
    }
    fun rebuild() { viewModelScope.launch { val result = repository.rebuildIndex(); if (result is NoteResult.StorageFailure) mutableState.value = mutableState.value.copy(error = result.reason) else refresh() } }
    suspend fun loadThumbnail(id: NoteId, target: String, syntax: LinkSyntax): ImageBitmap? = thumbnailLoader?.load(id, target, syntax)
    fun quick(onCreated: (NoteId) -> Unit) = create("快速记录-${UUID.randomUUID().toString().take(8)}", onCreated)
    suspend fun retire() {
        // 图片请求由界面协程发起，恢复前也要终止旧加载器的交付能力。
        thumbnailLoader?.retire()
        viewModelScope.coroutineContext[Job]?.cancelAndJoin()
    }

    fun create(name: String, onCreated: (NoteId) -> Unit) {
        if (mutableState.value.creating) return
        // 输入为不含扩展名的单个文件名；目录组织留在后续功能轮次。
        if (!LibraryPath.isValid(name) || name.contains('/') || name.contains('\\')) {
            mutableState.value = mutableState.value.copy(error = "名称不能包含路径、非法字符、保留名或尾随空格/点")
            return
        }
        mutableState.value = mutableState.value.copy(creating = true, error = null)
        viewModelScope.launch {
            val folder = mutableState.value.query.folder
            val result = repository.create((if (folder == null) "" else "$folder/") + "$name.md", RawDocument(""))
            mutableState.value = mutableState.value.copy(creating = false, error = when (result) {
                is NoteResult.Committed -> null
                NoteResult.PathConflict -> "同名笔记已存在（含大小写冲突）"
                is NoteResult.InvalidPath -> result.reason
                is NoteResult.StorageFailure -> result.reason
                else -> "创建失败，请重试"
            })
            if (result is NoteResult.Committed) onCreated(result.value.summary.id)
        }
    }
}
