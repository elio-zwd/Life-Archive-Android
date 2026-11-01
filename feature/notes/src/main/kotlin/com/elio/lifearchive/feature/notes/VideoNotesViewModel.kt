package com.elio.lifearchive.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.preferences.PreferencesRepository
import com.elio.lifearchive.core.preferences.VideoPromptType
import com.elio.lifearchive.domain.notes.NoteId
import com.elio.lifearchive.domain.notes.NoteResult
import com.elio.lifearchive.domain.notes.NoteSnapshot
import com.elio.lifearchive.domain.notes.NotesRepository
import com.elio.lifearchive.domain.notes.failureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val commonVideoPrompt = "请将这个视频整理为方便以后查阅的中文 Markdown 笔记，直接输出正文，不要用代码块包裹全文。保留关键条件、数字、例子和注意事项，避免笼统概括。明确区分作者观点和视频提供的依据，没有提到的信息不要补造。不要虚构来源链接，不必生成 YAML 或标签。无内容的章节省略。"

val VideoPromptType.label: String get() = when (this) {
    VideoPromptType.GENERAL -> "通用摘记"
    VideoPromptType.HOW_TO -> "操作技巧"
    VideoPromptType.EXPLANATION -> "知识讲解"
    VideoPromptType.CHOOSING -> "选择与避坑"
}

val VideoPromptType.defaultPrompt: String get() = commonVideoPrompt + "\n\n" + when (this) {
    VideoPromptType.GENERAL -> "按“核心结论、关键要点、适用场景”组织，保留有助于理解的例子。"
    VideoPromptType.HOW_TO -> "按“解决什么问题、准备与条件、操作步骤、注意事项”组织，步骤按执行顺序排列，保留关键参数。"
    VideoPromptType.EXPLANATION -> "按“核心概念、原理解释、具体例子、适用边界”组织，说明概念之间的关系。"
    VideoPromptType.CHOOSING -> "按“选择目标、比较维度、各方案优缺点、适合谁、常见坑”组织，明确推荐依据；视频没有比较的内容不要补造。"
}

private fun defaultVideoPrompts() = VideoPromptType.entries.associateWith { it.defaultPrompt }

data class VideoNotesUiState(
    val tab: Int = 0,
    val selectedType: VideoPromptType = VideoPromptType.GENERAL,
    val prompts: Map<VideoPromptType, String> = defaultVideoPrompts(),
    val savedPrompts: Map<VideoPromptType, String> = defaultVideoPrompts(),
    val loadingPrompts: Boolean = true,
    val promptsLoaded: Boolean = false,
    val name: String = "",
    val body: String = "",
    val source: String = "",
    val savingPrompt: Boolean = false,
    val savingNote: Boolean = false,
    val createdNote: NoteId? = null,
    val pendingPaste: String? = null,
    val confirmLeave: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val warning: String? = null,
) {
    val busy get() = savingPrompt || savingNote || createdNote != null
    val hasUnsavedInput get() = name.isNotEmpty() || body.isNotEmpty() || source.isNotEmpty() || prompts != savedPrompts
    val prompt get() = prompts.getValue(selectedType)
}

/** 保留正文全部字节顺序；来源只追加在末尾。 */
internal fun videoSummaryBody(body: String, source: String): String =
    if (source.isBlank()) body else {
        val newline = if (body.contains("\r\n")) "\r\n" else "\n"
        body + newline + newline + "## 来源" + newline + source
    }

class VideoNotesViewModel(
    private val notes: NotesRepository,
    private val preferences: PreferencesRepository,
    val folder: String?,
    val captureOnly: Boolean = false,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VideoNotesUiState(tab = if (captureOnly) 1 else 0))
    val state = mutableState.asStateFlow()
    private var readingPrompts = false
    private var initialPasteHandled = false
    private var manualName = false
    private var knownPaths: List<String> = emptyList()

    init {
        if (!captureOnly) loadPrompts()
        viewModelScope.launch {
            try { notes.observeNotes().collect { values -> knownPaths = values.map { it.path.value } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 创建仍通过 Repository 的路径冲突结果兜底。 */ }
        }
    }

    fun initialPaste(read: () -> String?) {
        if (initialPasteHandled) return
        initialPasteHandled = true
        try { paste(read()) }
        catch (e: Exception) { error("读取剪贴板失败：${e.message ?: "请手动粘贴"}") }
    }

    private fun uniqueName(base: String): String {
        for (index in 0..1000) {
            val name = pastedFileName(base, folder, if (index == 0) "" else " ($index)")
            val path = (folder?.let { "$it/" } ?: "") + "$name.md"
            if (!LibraryPath.conflicts(path, knownPaths)) return name
        }
        throw IllegalStateException("同名笔记过多，请修改名称")
    }

    private fun applyPaste(text: String) {
        try {
            val parsed = parsePastedNote(text, folder)
            mutableState.value = state.value.copy(body = parsed.body,
                name = if (manualName) state.value.name else uniqueName(parsed.name), warning = parsed.warning, error = null)
        } catch (e: Exception) { mutableState.value = state.value.copy(body = text, warning = "识别失败，原文已保留：${e.message}") }
    }

    fun loadPrompts() {
        if (readingPrompts || state.value.promptsLoaded || state.value.busy) return
        readingPrompts = true
        mutableState.value = state.value.copy(loadingPrompts = true, error = null)
        viewModelScope.launch {
            try {
                val overrides = preferences.preferences.first().videoPrompts
                val prompts = defaultVideoPrompts() + overrides
                mutableState.value = state.value.copy(prompts = prompts, savedPrompts = prompts, loadingPrompts = false, promptsLoaded = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = state.value.copy(loadingPrompts = false, error = "提示词读取失败：${e.message ?: "请重试"}") }
            finally { readingPrompts = false }
        }
    }

    fun tab(value: Int) { if (!state.value.busy) mutableState.value = state.value.copy(tab = value) }
    fun select(type: VideoPromptType) { if (!state.value.busy) mutableState.value = state.value.copy(selectedType = type) }
    fun prompt(value: String) {
        if (!state.value.busy && state.value.promptsLoaded) mutableState.value = state.value.copy(prompts = state.value.prompts + (state.value.selectedType to value), message = null, error = null)
    }
    fun name(value: String) { if (!state.value.busy) { manualName = value.isNotBlank(); mutableState.value = state.value.copy(name = value, error = null) } }
    fun body(value: String) { if (!state.value.busy) mutableState.value = state.value.copy(body = value, error = null) }
    fun source(value: String) { if (!state.value.busy) mutableState.value = state.value.copy(source = value, error = null) }

    fun message(value: String) { mutableState.value = state.value.copy(message = value, error = null) }
    fun error(value: String) { mutableState.value = state.value.copy(error = value, message = null) }

    fun savePrompt(restoreDefault: Boolean = false) {
        val current = state.value
        if (current.busy || !current.promptsLoaded) return
        val type = current.selectedType
        val text = if (restoreDefault) type.defaultPrompt else current.prompt
        mutableState.value = current.copy(savingPrompt = true, message = null, error = null)
        viewModelScope.launch {
            try {
                preferences.setVideoPrompt(type, if (restoreDefault || text == type.defaultPrompt) null else text)
                mutableState.value = state.value.copy(
                    prompts = state.value.prompts + (type to text), savedPrompts = state.value.savedPrompts + (type to text),
                    message = if (restoreDefault) "已恢复${type.label}默认提示词" else "提示词已保存",
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("提示词保存失败：${e.message ?: "请重试"}") }
            finally { mutableState.value = state.value.copy(savingPrompt = false) }
        }
    }

    fun paste(text: String?) {
        if (state.value.busy) return
        when {
            text.isNullOrBlank() -> error("剪贴板为空或不含可收录的文字")
            state.value.body.isNotEmpty() -> mutableState.value = state.value.copy(pendingPaste = text)
            else -> applyPaste(text)
        }
    }
    fun confirmPaste(accept: Boolean) {
        val text = state.value.pendingPaste ?: return
        mutableState.value = state.value.copy(pendingPaste = null)
        if (accept) applyPaste(text)
    }

    fun leave(onLeave: () -> Unit) {
        if (state.value.busy) return
        if (state.value.hasUnsavedInput) mutableState.value = state.value.copy(confirmLeave = true) else onLeave()
    }
    fun cancelLeave() { mutableState.value = state.value.copy(confirmLeave = false) }

    fun create() {
        if (state.value.busy) return
        if (state.value.body.isBlank()) { error("请先粘贴或填写总结正文"); return }
        val parsed = try { parsePastedNote(state.value.body, folder) }
        catch (e: Exception) { error("识别失败：${e.message}"); return }
        val name = try { state.value.name.ifBlank { uniqueName(parsed.name) } }
        catch (e: Exception) { error(e.message ?: "无法创建名称"); return }
        mutableState.value = state.value.copy(body = parsed.body, name = name, warning = parsed.warning)
        val current = state.value
        if (!LibraryPath.isValid(current.name) || current.name.contains('/') || current.name.contains('\\')) {
            error("笔记名不能为空，且不能包含路径、非法字符、保留名或尾随空格/点")
            return
        }
        mutableState.value = current.copy(savingNote = true, message = null, error = null)
        viewModelScope.launch {
            try {
                var candidate = current.name
                var result: NoteResult<NoteSnapshot> = NoteResult.PathConflict
                for (index in 0..100) {
                    candidate = fitPastedFileName(current.name, folder, if (index == 0) "" else " ($index)")
                    val path = (folder?.let { "$it/" } ?: "") + "$candidate.md"
                    result = notes.create(path, RawDocument(videoSummaryBody(current.body, current.source)))
                    if (result != NoteResult.PathConflict) break
                }
                mutableState.value = state.value.copy(name = candidate)
                if (result is NoteResult.Committed) mutableState.value = state.value.copy(createdNote = result.value.summary.id)
                else error("收录失败：${result.failureMessage()}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("收录失败：${e.message ?: "请重试"}") }
            finally { mutableState.value = state.value.copy(savingNote = false) }
        }
    }

    /** 导航确认消费后再清表单，避免重建页面重复创建或丢掉失败输入。 */
    fun noteOpened() { manualName = false; mutableState.value = state.value.copy(name = "", body = "", source = "", warning = null, createdNote = null, message = "笔记已保存") }
}
