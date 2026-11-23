package com.elio.lifearchive.feature.pet

import androidx.lifecycle.*
import com.elio.lifearchive.domain.pet.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionViewModelTest {
    private class Repository : CompanionRepository {
        override val state = MutableStateFlow(CompanionState(true, CompanionProfile(draft = "已有草稿")))
        val sendGate = CompletableDeferred<Unit>()
        var sends = 0
        var draftGate: CompletableDeferred<Unit>? = null
        var memoryGate: CompletableDeferred<Unit>? = null
        var memories = 0
        var rememberError: Exception? = null
        val drafts = mutableListOf<String>()
        override suspend fun initialize() {}
        override suspend fun updateOverlay(transform: (OverlaySettings) -> OverlaySettings) { state.value = state.value.copy(overlay = transform(state.value.overlay)) }
        override fun localReaction(trigger: CompanionTrigger) = CompanionGreeting("嗯，我在。")
        override suspend fun greeting(trigger: CompanionTrigger, now: Long, hour: Int, day: String): CompanionGreeting? = null
        override suspend fun updateProfile(name: String, personality: String) {}
        override suspend fun saveDraft(text: String) {
            draftGate?.await(); drafts += text
            state.value = state.value.copy(profile = state.value.profile.copy(draft = text))
        }
        override suspend fun setInAppVisible(visible: Boolean) {}
        override suspend fun send(text: String, operationId: String): Boolean { sends++; sendGate.await(); return true }
        override suspend fun retry(messageId: String) = false
        override suspend fun cancel() {}
        override suspend fun remember(text: String, sourceMessageId: String?) { memoryGate?.await(); rememberError?.let { throw it }; memories++ }
        override suspend fun editMemory(id: String, revision: Long, text: String) = false
        override suspend fun deleteMemory(id: String, revision: Long) = false
    }
    @Test fun repeatedClickCannotDuplicateSendAndNewTypingIsNotClearedByOldReceipt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository(); val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
        val model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CompanionViewModel(repo) as T
        })[CompanionViewModel::class.java]
        try {
            runCurrent(); assertEquals("已有草稿", model.draft.value)
            model.draft("第一条"); model.send(); model.send(); runCurrent()
            assertEquals(1, repo.sends)
            model.draft("新的输入"); runCurrent()
            repo.sendGate.complete(Unit); runCurrent()
            assertEquals("新的输入", model.draft.value)
            assertEquals(listOf("第一条", "新的输入"), repo.drafts)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun staleMemoryEditIsReportedInsteadOfClaimingSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository(); val model = CompanionViewModel(repo)
        try {
            runCurrent(); model.edit(CompanionMemory("id", "old", null, 1), "new"); runCurrent()
            assertEquals("记忆已变化，请重新打开后编辑。", model.error.value)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun externalWindowDraftIsAdoptedWithoutReplacingUncommittedLocalTyping() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository(); val model = CompanionViewModel(repo)
        try {
            runCurrent()
            repo.state.value = repo.state.value.copy(profile = repo.state.value.profile.copy(draft = "系统窗口中的草稿"))
            runCurrent(); assertEquals("系统窗口中的草稿", model.draft.value)
            val gate = CompletableDeferred<Unit>(); repo.draftGate = gate
            model.draft("新输入尚未落盘"); runCurrent()
            repo.state.value = repo.state.value.copy(profile = repo.state.value.profile.copy(draft = "较早的外部回执"))
            runCurrent(); assertEquals("新输入尚未落盘", model.draft.value)
            gate.complete(Unit); runCurrent()
            assertEquals("新输入尚未落盘", repo.state.value.profile.draft)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun sheetGetsFalseForAStaleRevisionInsteadOfDismissingAsSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = CompanionViewModel(Repository())
        try {
            runCurrent()
            assertFalse(model.saveMemory(CompanionMemory("id", "old", null, 1), "edited", null))
            assertEquals("记忆已变化，请重新打开后编辑。", model.error.value)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun losingTheSheetWaiterDoesNotRepeatAnAlreadyCommittedMemory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository(); val gate = CompletableDeferred<Unit>(); repo.memoryGate = gate
        val model = CompanionViewModel(repo)
        try {
            runCurrent()
            val waiter = launch { model.saveMemory(null, "重要的小事", null, "same-sheet") }
            runCurrent(); waiter.cancel(); gate.complete(Unit); runCurrent()
            assertEquals("same-sheet", model.savedMemorySubmission.value)
            assertTrue(model.saveMemory(null, "重要的小事", null, "same-sheet"))
            assertEquals(1, repo.memories)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun validationErrorMessageIsSurfacedAndOverlongDraftIsClipped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository().apply { rememberError = IllegalArgumentException("记忆已达 500 条，请先整理。") }
        val model = CompanionViewModel(repo)
        try {
            runCurrent()
            assertFalse(model.saveMemory(null, "第 501 条", null))
            assertEquals("记忆已达 500 条，请先整理。", model.error.value)
            model.draft("a".repeat(16_050)); runCurrent()
            assertEquals(16_000, model.draft.value.length)
            assertEquals(16_000, repo.drafts.last().length)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
