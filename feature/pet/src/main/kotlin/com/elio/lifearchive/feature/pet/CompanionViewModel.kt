package com.elio.lifearchive.feature.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.domain.pet.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    init { initialize() }
    fun initialize() = action {
        repository.initialize()
        mutableDraft.value = repository.state.value.profile.draft
    }
    fun draft(value: String) {
        if (value.length > 16_000) return
        mutableDraft.value = value
        val previous = draftWrite
        draftWrite = action { previous?.join(); repository.saveDraft(value) }
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
                    if (mutableDraft.value == text) mutableDraft.value = ""
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
    private fun action(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = "操作未完成，请重试。" }
    }
}
