package com.elio.lifearchive.feature.editor

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.domain.notes.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

data class EditorUiState(
    val loading: Boolean = true, val name: String = "", val error: String? = null,
    val mode: EditorMode = EditorMode.READ, val parsed: ParsedDocument? = null,
    val pendingDraft: Draft? = null, val draftConflict: Boolean = false,
    val message: String? = null, val busy: Boolean = false,
    val projection: EditorRenderProjection? = null,
)

class EditorViewModel(private val repository: NotesRepository, val id: NoteId, startEditing: Boolean, private val location: Int? = null, private val parse: suspend (String) -> ParsedDocument = { text -> withContext(Dispatchers.Default) { MarkdownParser().parse(text) } }) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorUiState(mode = if (startEditing) EditorMode.SOURCE else EditorMode.READ))
    val state = mutableState.asStateFlow()
    var session by mutableStateOf<EditorSession?>(null); private set
    var persistence: EditorPersistence? = null; private set
    private var snapshot: NoteSnapshot? = null
    private var readingOffset = 0
    private var entryOffset = 0
    val initialReadingOffset get() = entryOffset
    private var revision = 0L
    private var retired = false
    private var simulateFailure = false
    private val sessionRepository = object : NotesRepository by repository {
        override suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long?): NoteResult<NoteSnapshot> =
            if (simulateFailure) NoteResult.StorageFailure("样板模拟：正文写入失败") else repository.save(id, expected, document, acknowledgedDraftRevision)
    }

    init {
        viewModelScope.launch {
            val read = repository.read(id)
            if (read !is NoteResult.Success) { mutableState.value = mutableState.value.copy(loading = false, error = read.explanation()); return@launch }
            snapshot = read.value
            val draftResult = repository.readDraft(id)
            if (draftResult !is NoteResult.Success) { mutableState.value = mutableState.value.copy(loading = false, error = draftResult.explanation()); return@launch }
            val draft = draftResult.value
            readingOffset = location ?: (repository.readReadingOffset(id) as? NoteResult.Success)?.value ?: 0
            entryOffset = readingOffset
            val newSession = EditorSession(read.value.document.text)
            if (location != null) newSession.text.edit { selection = TextRange(location.coerceIn(0, length)) }
            session = newSession
            persistence = EditorPersistence(sessionRepository, read.value, viewModelScope, draft?.revision ?: 0)
            val pending = draft?.takeIf { it.document != read.value.document }
            if (pending != null) persistence?.blockAutomaticSave()
            mutableState.value = mutableState.value.copy(loading = false, name = read.value.summary.path.value.removeSuffix(".md"), mode = if (pending != null) EditorMode.READ else mutableState.value.mode, pendingDraft = pending, draftConflict = pending != null && pending.baseVersion != read.value.summary.version)
            launch {
                snapshotFlow { newSession.text.text.toString() }.collect { text ->
                    if (mutableState.value.pendingDraft == null) persistence?.changed(text)
                }
            }
            launch {
                snapshotFlow { newSession.text.text.toString() }.collectLatest { text ->
                    val captured = ++revision
                    val previous = mutableState.value.projection
                    val updated = if (previous != null) withContext(Dispatchers.Default) { previous.reparseChangedBlock(text) } else null
                    if (updated != null && !retired && captured == revision && newSession.text.text.toString() == text) mutableState.value = mutableState.value.copy(projection = EditorRenderProjection(captured, updated))
                    val parsed = parse(text)
                    if (!retired && captured == revision && newSession.text.text.toString() == text) mutableState.value = mutableState.value.copy(parsed = parsed, projection = EditorRenderProjection(captured, parsed))
                }
            }
        }
    }

    fun changeMode(mode: EditorMode) {
        if (retired) return
        if (mutableState.value.pendingDraft != null && mode != EditorMode.READ) return
        val editor = session ?: return
        if (mode == EditorMode.READ) { readingOffset = editor.text.selection.start; entryOffset = readingOffset }
        else if (mutableState.value.mode == EditorMode.READ) editor.text.edit { selection = TextRange(readingOffset.coerceIn(0, length)) }
        mutableState.value = mutableState.value.copy(mode = mode)
        if (mode == EditorMode.READ) viewModelScope.launch { flush() }
    }

    fun restoreDraft() {
        if (retired) return
        val draft = mutableState.value.pendingDraft ?: return
        if (mutableState.value.draftConflict) return
        val original = snapshot ?: return
        persistence = EditorPersistence(sessionRepository, original, viewModelScope, draft.revision)
        session?.text?.edit { replace(0, length, draft.document.text); selection = TextRange(0) }
        persistence?.changed(draft.document.text)
        mutableState.value = mutableState.value.copy(pendingDraft = null, mode = EditorMode.SOURCE, message = "已恢复草稿，继续编辑后自动保存")
    }

    fun copyDraft(onCreated: (NoteId) -> Unit) {
        val document = mutableState.value.pendingDraft?.document ?: session?.let { RawDocument(it.text.text.toString()) } ?: return
        if (retired || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            val result = repository.create("恢复的草稿-${UUID.randomUUID().toString().take(8)}.md", document)
            mutableState.value = mutableState.value.copy(busy = false, message = if (result is NoteResult.Committed) "草稿已另存，原笔记与原草稿均保留" else result.explanation())
            if (result is NoteResult.Committed) onCreated(result.value.summary.id)
        }
    }

    fun readingPosition(offset: Int) { if (!retired) readingOffset = offset.coerceAtLeast(0) }
    fun message(value: String) { if (!retired) mutableState.value = mutableState.value.copy(message = value) }
    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }
    fun applyTags(tags: List<String>): Boolean {
        if (retired) return false
        if (state.value.pendingDraft != null) { message("请先处理草稿"); return false }
        val editor = session ?: return false
        if (editor.text.composition != null) { message("请完成当前输入后再修改标签"); return false }
        editor.text.edit {
            val raw = asCharSequence().toString(); val changed = com.elio.lifearchive.core.document.DocumentAnalyzer.withTags(raw, tags)
            val cursor = selection.start; replace(0, length, changed); selection = TextRange((cursor + changed.length - raw.length).coerceIn(0, length))
        }
        return true
    }
    fun insertAttachment(path: String) {
        if (retired) return
        if (state.value.pendingDraft != null) { message("请先处理草稿"); return }
        if (session?.text?.composition != null) { message("附件已入库，请完成输入后插入：$path"); return }
        changeMode(EditorMode.SOURCE)
        session?.text?.edit { val at = selection.min; replace(selection.min, selection.max, "![[${path}]]"); selection = TextRange(at + path.length + 5) }
    }
    fun backgroundSave() { viewModelScope.launch { flush() } }

    suspend fun flush(): Boolean {
        if (retired) return true
        val editor = session ?: return true // 尚未取得正文，没有本地输入需要保护。
        if (mutableState.value.pendingDraft != null) {
            repository.updateReadingOffset(id, readingOffset)
            return true // 尚未确认的草稿保持原样，不用阅读正文覆盖它。
        }
        persistence?.changed(editor.text.text.toString())
        persistence?.flush()
        val position = repository.updateReadingOffset(id, readingOffset)
        if (position !is NoteResult.Success) message("阅读位置保存失败：${position.explanation()}")
        // 等待磁盘时仍可能收到最后一批 IME 输入，离开前直接核对文本状态。
        persistence?.changed(editor.text.text.toString())
        if (persistence?.status?.value?.draftProtected != true) persistence?.protectDraft()
        return persistence?.status?.value?.draftProtected == true
    }

    fun leave(onLeave: () -> Unit) {
        if (retired || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            val safe = flush()
            mutableState.value = mutableState.value.copy(busy = false)
            if (safe && !simulateFailure) onLeave() else message("尚未安全保存，请重试后再离开。当前内容仍保留在编辑器中。")
        }
    }

    suspend fun retire(): Boolean {
        if (!flush()) return false
        retired = true
        viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        return true
    }

    fun setSimulatedFailure(enabled: Boolean) {
        simulateFailure = enabled
        message(if (enabled) "已启用本次会话的正文保存失败模拟" else "已关闭模拟，可点击重试")
    }

    suspend fun image(path: String): ByteArray? = withContext(Dispatchers.IO) {
        // 样板只读库内附件，不获取网络图片或将任意 URI 交给文件系统。
        if (!LibraryPath.isValid(path)) return@withContext null
        val output = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) { require(count.toLong() + len <= 16 * 1024 * 1024) { "图片超过样板解码上限" }; super.write(b, off, len) }
        }
        try { if (repository.copyAttachment(LibraryPath(path), output) is NoteResult.Success) output.toByteArray() else null } catch (_: IllegalArgumentException) { null }
    }
}
