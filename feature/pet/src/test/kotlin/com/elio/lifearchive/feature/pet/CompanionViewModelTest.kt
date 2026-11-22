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
        val drafts = mutableListOf<String>()
        override suspend fun initialize() {}
        override suspend fun updateProfile(name: String, personality: String) {}
        override suspend fun saveDraft(text: String) { drafts += text }
        override suspend fun setInAppVisible(visible: Boolean) {}
        override suspend fun send(text: String, operationId: String): Boolean { sends++; sendGate.await(); return true }
        override suspend fun retry(messageId: String) = false
        override suspend fun cancel() {}
        override suspend fun remember(text: String, sourceMessageId: String?) {}
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
}
