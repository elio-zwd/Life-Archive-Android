package com.elio.lifearchive.feature.editor

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.core.designsystem.SourceLinkDialogState
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
    val path: String? = null, val version: String? = null,
    val commandRunning: Boolean = false, val insertion: EditorInsertion? = null,
    val linkChoices: List<NoteLinkChoice> = emptyList(), val linkLoading: Boolean = false,
    val insertionError: String? = null, val attachmentToInsert: String? = null,
    val sourceLink: SourceLinkDialogState? = null,
)

enum class EditorInsertKind { EXTERNAL_LINK, WIKI_LINK, CODE_BLOCK, CALLOUT }
data class EditorInsertion(val key: String, val kind: EditorInsertKind, val anchor: EditorAnchor, val label: String = "", val target: String = "", val parameter: String = "", val query: String = "", val selected: NoteLinkChoice? = null, val markdown: Boolean = false)
data class NoteLinkChoice(val id: NoteId, val path: String, val title: String?)

class EditorViewModel(private val repository: NotesRepository, val id: NoteId, startEditing: Boolean, private val location: Int? = null, private val parse: suspend (String) -> ParsedDocument = { text -> withContext(Dispatchers.Default) { MarkdownParser().parse(text) } }, private val library: LibraryRepository? = null) : ViewModel() {
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
    private val prepareHighlight = MutableStateFlow(startEditing)
    private var simulateFailure = false
    private val openedAt = System.nanoTime()
    private var linkRequest = 0L
    private var attachmentAnchor: EditorAnchor? = null
    fun openElapsedMs(): Long = (System.nanoTime() - openedAt) / 1_000_000
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
            if (location != null) newSession.edit { selection = TextRange(location.coerceIn(0, length)) }
            session = newSession
            persistence = EditorPersistence(sessionRepository, read.value, viewModelScope, draft?.revision ?: 0)
            val pending = draft?.takeIf { it.document != read.value.document }
            if (pending != null) persistence?.blockAutomaticSave()
            mutableState.value = mutableState.value.copy(loading = false, name = read.value.summary.path.value.removeSuffix(".md"), path = read.value.summary.path.value, version = read.value.summary.version.value, mode = if (pending != null) EditorMode.READ else mutableState.value.mode, pendingDraft = pending, draftConflict = pending != null && pending.baseVersion != read.value.summary.version)
            val contents = snapshotFlow { newSession.content }.stateIn(viewModelScope, SharingStarted.Eagerly, newSession.content)
            launch {
                contents.collect { current ->
                    if (mutableState.value.pendingDraft == null) persistence?.changed(current.source)
                }
            }
            launch {
                combine(contents, prepareHighlight) { current, ready -> current to ready }.collectLatest { (current, ready) ->
                    if (!ready) return@collectLatest
                    val captured = ++revision
                    fun isCurrent() = !retired && captured == revision && newSession.content.revision == current.revision
                    val previous = mutableState.value.projection
                    if (previous != null) {
                        val (updated, display) = withContext(Dispatchers.Default) {
                            val document = previous.reparseChangedBlock(current.source)
                            document to EditorDisplaySnapshot(current.revision, document)
                        }
                        if (isCurrent()) {
                            newSession.installDisplay(current, display)
                            mutableState.value = mutableState.value.copy(projection = EditorRenderProjection(captured, updated))
                        }
                        kotlinx.coroutines.delay(200)
                    }
                    val parsed = parse(current.source)
                    val (context, display) = withContext(Dispatchers.Default) {
                        ContinuationContext.build(current.source) to EditorDisplaySnapshot(current.revision, parsed)
                    }
                    if (isCurrent()) {
                        newSession.installContext(current, context); newSession.installDisplay(current, display)
                        mutableState.value = mutableState.value.copy(parsed = parsed, projection = EditorRenderProjection(captured, parsed))
                    }
                }
            }
        }
    }

    fun changeMode(mode: EditorMode) {
        if (retired) return
        if (mutableState.value.pendingDraft != null && mode != EditorMode.READ) return
        val editor = session ?: return
        dismissInsertion()
        if (mode == EditorMode.READ) { readingOffset = editor.text.selection.start; entryOffset = readingOffset }
        else if (mutableState.value.mode == EditorMode.READ) editor.edit { selection = TextRange(readingOffset.coerceIn(0, length)) }
        mutableState.value = mutableState.value.copy(mode = mode)
        if (mode != EditorMode.READ) prepareHighlight.value = true
        if (mode == EditorMode.READ) viewModelScope.launch { flush() }
    }

    fun readerLoaded(event: ReaderLoadEvent) {
        if (!retired && event.phase == ReaderLoadPhase.FIRST_SCREEN) viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            if (!retired) prepareHighlight.value = true
        }
    }

    fun restoreDraft() {
        if (retired) return
        val draft = mutableState.value.pendingDraft ?: return
        if (mutableState.value.draftConflict) return
        val original = snapshot ?: return
        persistence = EditorPersistence(sessionRepository, original, viewModelScope, draft.revision)
        session?.edit { replace(0, length, draft.document.text); selection = TextRange(0) }
        persistence?.changed(draft.document.text)
        mutableState.value = mutableState.value.copy(pendingDraft = null, mode = EditorMode.SOURCE, message = "已恢复草稿，继续编辑后自动保存")
        prepareHighlight.value = true
    }

    fun copyDraft(onCreated: (NoteId) -> Unit) {
        val document = mutableState.value.pendingDraft?.document ?: session?.let { RawDocument(it.content.source) } ?: return
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
    private fun editable(): Boolean = !retired && !state.value.busy && state.value.pendingDraft == null && state.value.mode != EditorMode.READ && session?.canModify == true

    fun command(command: MarkdownCommand, anchor: EditorAnchor? = session?.anchor()) {
        performCommand(command, anchor)
    }
    private fun performCommand(command: MarkdownCommand, anchor: EditorAnchor?, onApplied: () -> Unit = {}) {
        if (!editable() || state.value.commandRunning || anchor == null) return
        val editor = session ?: return
        val prompt = state.value.insertion
        mutableState.value = state.value.copy(commandRunning = true)
        viewModelScope.launch {
            try {
                val edit = withContext(Dispatchers.Default) { editor.calculate(anchor, command) }
                if (!editable() || session !== editor) return@launch
                if (prompt != null && state.value.insertion != prompt) {
                    if (state.value.insertion != null) mutableState.value = state.value.copy(insertionError = "插入内容已变化，请重新确认")
                    return@launch
                }
                val error = editor.apply(anchor, edit)
                mutableState.value = state.value.copy(message = error, insertionError = error)
                if (error == null) { onApplied(); dismissInsertion() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message("格式操作未完成，原文保持不变，请重试") }
            finally { if (!retired) mutableState.value = state.value.copy(commandRunning = false) }
        }
    }

    fun beginInsertion(kind: EditorInsertKind) {
        if (!editable() || state.value.commandRunning) return
        val anchor = session?.anchor() ?: return
        mutableState.value = state.value.copy(insertion = EditorInsertion(UUID.randomUUID().toString(), kind, anchor, label = anchor.source.substring(anchor.selection.min, anchor.selection.max), parameter = if (kind == EditorInsertKind.CALLOUT) "NOTE" else ""), insertionError = null, linkChoices = emptyList())
        if (kind == EditorInsertKind.WIKI_LINK) searchLinkNotes("")
    }
    fun dismissInsertion() {
        linkRequest++
        if (state.value.insertion != null) session?.requestInputFocus()
        mutableState.value = state.value.copy(insertion = null, linkChoices = emptyList(), linkLoading = false, insertionError = null)
    }
    fun reanchorInsertion() {
        if (!editable()) return
        val anchor = session?.anchor() ?: return
        mutableState.value = state.value.copy(insertion = state.value.insertion?.copy(anchor = anchor), insertionError = null)
    }
    fun editInsertion(update: (EditorInsertion) -> EditorInsertion) {
        val old = state.value.insertion ?: return
        val changed = update(old)
        mutableState.value = state.value.copy(insertion = changed.copy(key = old.key, kind = old.kind, anchor = old.anchor), insertionError = null)
    }
    fun searchLinkNotes(query: String) {
        val request = state.value.insertion?.takeIf { it.kind == EditorInsertKind.WIKI_LINK } ?: return
        val sequence = ++linkRequest
        mutableState.value = state.value.copy(linkLoading = true, insertionError = null, insertion = request.copy(query = query, selected = null))
        viewModelScope.launch {
            val result = try { library?.query(NotesQuery(text = query)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (retired || sequence != linkRequest || state.value.insertion?.key != request.key) return@launch
            mutableState.value = state.value.copy(linkLoading = false,
                linkChoices = (result as? NoteResult.Success)?.value?.notes?.map { NoteLinkChoice(it.summary.id, it.summary.path.value, it.title) }.orEmpty(),
                insertionError = if (result is NoteResult.Success) null else "查询笔记失败，请重试")
        }
    }
    fun insertExternal(label: String, target: String) {
        val prompt = state.value.insertion?.takeIf { it.kind == EditorInsertKind.EXTERNAL_LINK } ?: return
        val markup = try { MarkdownReferences.external(label, target) } catch (e: IllegalArgumentException) { mutableState.value = state.value.copy(insertionError = e.message); return }
        command(MarkdownCommand.InsertLink(markup), prompt.anchor)
    }
    fun insertTemplateFromPrompt(parameter: String) {
        val prompt = state.value.insertion?.takeIf { it.kind == EditorInsertKind.CODE_BLOCK || it.kind == EditorInsertKind.CALLOUT } ?: return
        val kind = if (prompt.kind == EditorInsertKind.CODE_BLOCK) InsertTemplate.CODE_BLOCK else InsertTemplate.CALLOUT
        command(MarkdownCommand.Template(kind, parameter), prompt.anchor)
    }
    fun insertNoteLink(choice: NoteLinkChoice, label: String, markdown: Boolean = false) {
        val prompt = state.value.insertion?.takeIf { it.kind == EditorInsertKind.WIKI_LINK } ?: return
        if (!editable() || state.value.commandRunning) return
        viewModelScope.launch {
            val latest = try { repository.read(choice.id) as? NoteResult.Success } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (!editable() || state.value.insertion != prompt) return@launch
            if (latest == null) { mutableState.value = state.value.copy(insertionError = "目标笔记已不可用，请重新选择"); return@launch }
            val path = latest.value.summary.path
            val display = label.ifBlank { path.value.substringAfterLast('/').removeSuffix(".md") }
            val wiki = MarkdownReferences.wiki(path, display)
            if (!markdown && wiki == null) { mutableState.value = state.value.copy(insertionError = "此名称不适合双链，请选择下方的 Markdown 链接方式"); return@launch }
            val from = state.value.path?.let(::LibraryPath) ?: return@launch
            val target = if (markdown) MarkdownReferences.relative(from, path, label) else wiki!!
            val syntax = if (markdown) LinkSyntax.MARKDOWN else LinkSyntax.WIKI
            val linkTarget = if (markdown) DocumentAnalyzer.analyze(target).links.singleOrNull()?.target else path.value
            val resolved = try { linkTarget?.let { library?.resolve(id, it, syntax) } as? NoteResult.Success } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (!editable() || state.value.insertion != prompt) return@launch
            if (resolved?.value?.note != choice.id) { mutableState.value = state.value.copy(insertionError = "无法确认链接目标，请刷新后重新选择"); return@launch }
            command(MarkdownCommand.InsertLink(target), prompt.anchor)
        }
    }

    fun prepareAttachment(): Boolean {
        if (retired || state.value.busy || state.value.commandRunning || state.value.pendingDraft != null || session?.canModify != true) return false
        if (state.value.mode == EditorMode.READ) changeMode(EditorMode.SOURCE)
        attachmentAnchor = session?.anchor()
        return attachmentAnchor != null
    }
    fun cancelAttachment() { attachmentAnchor = null }

    private fun sourceLinkBlock(): String? = when {
        retired || state.value.loading || session == null -> "笔记尚未就绪"
        state.value.pendingDraft != null -> "请先处理草稿，再更新链接"
        state.value.busy || state.value.commandRunning || persistence?.writing == true -> "请等待当前操作完成后再更新链接"
        session?.canModify != true -> "请完成当前输入后再更新链接"
        persistence?.status?.value?.conflict == true -> "正文存在版本冲突，请先处理后再更新链接"
        else -> null
    }
    fun openSourceLink(read: () -> String?) {
        sourceLinkBlock()?.let { message(it); return }
        val current = runCatching { NoteSource.read(session!!.content.source).orEmpty() }.getOrDefault("无法识别，请在源码中查看")
        mutableState.value = state.value.copy(sourceLink = SourceLinkDialogState(current = current), message = null)
        pasteSourceLink(read)
    }
    fun pasteSourceLink(read: () -> String?) {
        if (retired || state.value.busy || state.value.sourceLink == null) return
        try { sourceLinkText(read().orEmpty()) }
        catch (e: Exception) { mutableState.value = state.value.copy(sourceLink = state.value.sourceLink?.copy(error = "读取剪贴板失败，请手动粘贴")) }
    }
    fun sourceLinkText(text: String) {
        val dialog = state.value.sourceLink ?: return
        if (retired || state.value.busy) return
        val links = extractSharedWebLinks(text)
        mutableState.value = state.value.copy(sourceLink = dialog.copy(text = text, links = links, selected = links.singleOrNull(),
            error = if (links.isEmpty()) "未识别到 HTTP／HTTPS 链接，剪贴板可能为空或不含文字，可手动输入。" else null))
    }
    fun selectSourceLink(url: String) {
        val dialog = state.value.sourceLink ?: return
        if (!retired && !state.value.busy && url in dialog.links) mutableState.value = state.value.copy(sourceLink = dialog.copy(selected = url, error = null))
    }
    fun dismissSourceLink() { if (!state.value.busy) mutableState.value = state.value.copy(sourceLink = null) }
    fun applySourceLink() {
        val dialog = state.value.sourceLink ?: return
        val url = dialog.selected ?: return
        sourceLinkBlock()?.let { mutableState.value = state.value.copy(sourceLink = dialog.copy(error = it)); return }
        val editor = session ?: return
        val coordinator = persistence ?: return
        val before = editor.content.source
        val changed = try { NoteSource.withUrl(before, url) }
        catch (e: Exception) { mutableState.value = state.value.copy(sourceLink = dialog.copy(error = e.message ?: "链接未修改，请重试")); return }
        mutableState.value = state.value.copy(busy = true, message = null, sourceLink = dialog.copy(saving = true, error = null))
        if (changed != before) editor.edit {
            val cursor = selection.start
            replace(0, length, changed)
            selection = TextRange((cursor + changed.length - before.length).coerceIn(0, length))
        }
        coordinator.changed(editor.content.source)
        viewModelScope.launch {
            try {
                val committed = coordinator.flush() && coordinator.fullyCommitted && editor.content.source == changed
                if (!retired) {
                    if (committed) mutableState.value = state.value.copy(sourceLink = null, message = "链接已更新")
                    else mutableState.value = state.value.copy(sourceLink = state.value.sourceLink?.copy(error = "链接尚未保存：${coordinator.status.value.error ?: "请重试"}"))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (!retired) mutableState.value = state.value.copy(sourceLink = state.value.sourceLink?.copy(error = "链接尚未保存：${e.message ?: "请重试"}")) }
            finally { if (!retired) mutableState.value = state.value.copy(busy = false, sourceLink = state.value.sourceLink?.copy(saving = false)) }
        }
    }

    fun applyTags(tags: List<String>): Boolean {
        if (retired) return false
        if (state.value.pendingDraft != null) { message("请先处理草稿"); return false }
        val editor = session ?: return false
        if (editor.text.composition != null) { message("请完成当前输入后再修改标签"); return false }
        editor.edit {
            val raw = asCharSequence().toString(); val changed = com.elio.lifearchive.core.document.DocumentAnalyzer.withTags(raw, tags)
            val cursor = selection.start; replace(0, length, changed); selection = TextRange((cursor + changed.length - raw.length).coerceIn(0, length))
        }
        return true
    }
    fun insertAttachment(path: String) {
        if (retired) return
        val anchor = attachmentAnchor
        attachmentAnchor = null
        if (!editable() || anchor == null || session?.matches(anchor) != true) {
            mutableState.value = state.value.copy(attachmentToInsert = path, message = "附件已入库，原插入位置已变化，可选择插入当前光标")
            return
        }
        insertAttachmentAt(path, anchor)
    }
    fun reinsertAttachment() {
        if (!editable()) return
        val path = state.value.attachmentToInsert ?: return
        session?.anchor()?.let { insertAttachmentAt(path, it) }
    }
    private fun insertAttachmentAt(path: String, anchor: EditorAnchor) {
        val from = state.value.path?.let(::LibraryPath) ?: return
        val target = runCatching { LibraryPath(path) }.getOrNull() ?: return
        val markup = MarkdownReferences.wiki(target, embed = true) ?: MarkdownReferences.relative(from, target, embed = true)
        mutableState.value = state.value.copy(attachmentToInsert = path)
        performCommand(MarkdownCommand.InsertLink(markup), anchor) { mutableState.value = state.value.copy(attachmentToInsert = null) }
    }
    fun backgroundSave() { viewModelScope.launch { flush() } }

    suspend fun flush(): Boolean {
        if (retired) return true
        val editor = session ?: return true // 尚未取得正文，没有本地输入需要保护。
        if (mutableState.value.pendingDraft != null) {
            repository.updateReadingOffset(id, readingOffset)
            return true // 尚未确认的草稿保持原样，不用阅读正文覆盖它。
        }
        persistence?.changed(editor.content.source)
        persistence?.flush()
        val position = repository.updateReadingOffset(id, readingOffset)
        if (position !is NoteResult.Success) message("阅读位置保存失败：${position.explanation()}")
        // 等待磁盘时仍可能收到最后一批 IME 输入，离开前直接核对文本状态。
        persistence?.changed(editor.content.source)
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
        session?.close()
        viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        return true
    }

    override fun onCleared() {
        retired = true
        session?.close()
        super.onCleared()
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
