package com.elio.lifearchive.feature.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.domain.pet.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CompanionViewModel(private val repository: CompanionRepository) : ViewModel() {
    val state = repository.state
    private val mutableDraft = MutableStateFlow("")
    val draft = mutableDraft.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var draftWrite: Job? = null
    private var sending = false
    private var operation = UUID.randomUUID().toString()
    private var memorySubmission: Pair<String, Deferred<Boolean>>? = null
    private val savedMemorySubmissionMutable = MutableStateFlow<String?>(null)
    val savedMemorySubmission = savedMemorySubmissionMutable.asStateFlow()
    init { initialize() }
    init {
        viewModelScope.launch {
            repository.state.map { it.ready to it.profile.draft }.distinctUntilChanged().collect { (ready, text) ->
                if (ready && draftWrite?.isActive != true && !sending) mutableDraft.value = text
            }
        }
    }
    fun initialize() = action {
        repository.initialize()
        mutableDraft.value = repository.state.value.profile.draft
    }
    fun draft(value: String) {
        if (value.length > 16_000) return
        mutableDraft.value = value
        val previous = draftWrite
        draftWrite = action {
            previous?.join(); repository.saveDraft(value)
            if (mutableDraft.value == value) mutableDraft.value = repository.state.value.profile.draft
        }
    }
    fun send() {
        if (sending || mutableDraft.value.isBlank()) return
        val text = mutableDraft.value
        val identity = operation
        sending = true
        action {
            try {
                draftWrite?.join()
                if (repository.send(text, identity)) {
                    if (mutableDraft.value == text) mutableDraft.value = repository.state.value.profile.draft
                    operation = UUID.randomUUID().toString()
                }
            } finally { sending = false }
        }
    }
    fun retry(id: String) = action { repository.retry(id) }
    fun cancel() = action { repository.cancel() }
    fun profile(name: String, personality: String) = action { repository.updateProfile(name, personality) }
    fun visible(visible: Boolean) = action { repository.setInAppVisible(visible) }
    fun memory(text: String, source: String? = null) = action { repository.remember(text, source) }
    fun edit(memory: CompanionMemory, text: String) = action {
        if (!repository.editMemory(memory.id, memory.revision, text)) mutableError.value = "记忆已变化，请重新打开后编辑。"
    }
    fun delete(memory: CompanionMemory) = action {
        if (!repository.deleteMemory(memory.id, memory.revision)) mutableError.value = "记忆已变化，请重新打开。"
    }
    fun dismissError() { mutableError.value = null }
    suspend fun saveMemory(memory: CompanionMemory?, text: String, source: String?, submission: String = UUID.randomUUID().toString()): Boolean {
        // 表单旋转只取消等待者；同一次提交由 ViewModel 继续确认，避免恢复后重复新增。
        val task = memorySubmission?.takeIf { it.first == submission }?.second ?: viewModelScope.async {
            save {
                if (memory == null) { repository.remember(text, source); true }
                else repository.editMemory(memory.id, memory.revision, text).also {
                    if (!it) mutableError.value = "记忆已变化，请重新打开后编辑。"
                }
            }.also { if (it) savedMemorySubmissionMutable.value = submission }
        }.also { memorySubmission = submission to it }
        val result = task.await()
        if (!result && memorySubmission?.second === task) memorySubmission = null
        return result
    }
    suspend fun saveProfile(name: String, personality: String): Boolean = save {
        repository.updateProfile(name, personality); true
    }
    private suspend fun save(block: suspend () -> Boolean): Boolean = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { mutableError.value = "操作未完成，请重试。"; false }
    private fun action(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = "操作未完成，请重试。" }
    }
}
