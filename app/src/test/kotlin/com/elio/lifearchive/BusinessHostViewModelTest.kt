package com.elio.lifearchive

import androidx.lifecycle.*
import com.elio.jianyu.JianyuServices
import com.elio.jianyu.runtime.*
import com.elio.jianyu.viewmodel.ConversationSession
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BusinessHostViewModelTest {
    @Test fun petSelectionDoesNotInitializeJianyuAndSurvivesReconstruction() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ModelOwner(FakeRuntime(), SavedStateHandle(mapOf("host.selected-business" to "PET")))
        try {
            runCurrent()
            assertEquals(Business.PET, owner.model.selected.value)
            owner.model.select(Business.LIFE); owner.model.select(Business.PET)
            runCurrent()
            assertEquals(0, owner.providerCalls)
            assertEquals(JianyuHostState.Idle, owner.model.jianyu.value)
        } finally { owner.viewModelStore.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun freshLaunchDoesNotCreateJianyuRuntime() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val runtime = FakeRuntime()
        val owner = ModelOwner(runtime)
        try {
            runCurrent()
            assertEquals(Business.LIFE, owner.model.selected.value)
            assertEquals(JianyuHostState.Idle, owner.model.jianyu.value)
            assertEquals(0, owner.providerCalls)
            assertEquals(0, runtime.initializeCalls)
        } finally { owner.viewModelStore.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun repeatedSwitchingKeepsOneSessionAndItsRunningTask() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val runtime = FakeRuntime()
        val owner = ModelOwner(runtime)
        try {
            repeat(8) { owner.model.select(Business.JIANYU); owner.model.select(Business.LIFE) }
            runCurrent()
            assertEquals(1, runtime.initializeCalls)
            runtime.openGate.complete(Unit)
            runCurrent()
            val first = owner.model.jianyu.value as JianyuHostState.Ready
            val task = backgroundScope.launch { awaitCancellation() }
            runtime.sessions.single().task = task
            repeat(8) { owner.model.select(Business.JIANYU); owner.model.select(Business.LIFE) }
            runCurrent()
            assertSame(first, owner.model.jianyu.value)
            assertEquals(1, runtime.sessions.size)
            assertEquals(1, runtime.initializeCalls)
            assertTrue(task.isActive)
            assertEquals(0, runtime.sessions.single().releases)
        } finally { owner.viewModelStore.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun restoredActivityStateKeepsCurrentBusiness() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val runtime = FakeRuntime().also { it.openGate.complete(Unit) }
        val owner = ModelOwner(runtime, SavedStateHandle(mapOf("host.selected-business" to "JIANYU")))
        try {
            runCurrent()
            assertEquals(Business.JIANYU, owner.model.selected.value)
            assertTrue(owner.model.jianyu.value is JianyuHostState.Ready)
            assertEquals(1, runtime.initializeCalls)
        } finally { owner.viewModelStore.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun failureAllowsLifeAndRequiresExplicitRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val runtime = FakeRuntime().also { it.fail = true; it.openGate.complete(Unit) }
        val owner = ModelOwner(runtime)
        try {
            owner.model.select(Business.JIANYU)
            runCurrent()
            assertEquals(JianyuHostState.Failed, owner.model.jianyu.value)
            owner.model.select(Business.LIFE)
            assertEquals(Business.LIFE, owner.model.selected.value)
            owner.model.select(Business.JIANYU)
            runCurrent()
            assertEquals(1, runtime.initializeCalls)
            runtime.fail = false
            owner.model.retryJianyu()
            runCurrent()
            assertEquals(2, runtime.initializeCalls)
            assertTrue(owner.model.jianyu.value is JianyuHostState.Ready)
        } finally { owner.viewModelStore.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun maintenanceReleasesOldConsumersBeforeOpeningNewGeneration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val runtime = FakeRuntime().also { it.openGate.complete(Unit) }
        val owner = ModelOwner(runtime)
        try {
            owner.model.select(Business.JIANYU)
            runCurrent()
            val old = runtime.sessions.single()
            old.task = backgroundScope.launch { awaitCancellation() }
            runCurrent()
            runtime.availability.value = JianyuAvailability.Maintenance(1)
            runCurrent()
            assertEquals(JianyuHostState.Maintenance, owner.model.jianyu.value)
            assertEquals(1, old.releases)
            assertTrue(requireNotNull(old.task).isCompleted)
            runtime.availability.value = JianyuAvailability.Ready(2)
            runCurrent()
            val current = owner.model.jianyu.value as JianyuHostState.Ready
            assertEquals(2, current.session.generation)
            assertNotSame(old, current.session)
            assertEquals(2, runtime.sessions.size)
        } finally { owner.viewModelStore.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private class ModelOwner(runtime: FakeRuntime, state: SavedStateHandle = SavedStateHandle()) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        var providerCalls = 0
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BusinessHostViewModel(state, Provider { providerCalls++; runtime }) as T
        })[BusinessHostViewModel::class.java]
    }

    private class FakeRuntime : JianyuHostRuntime {
        override val availability = MutableStateFlow<JianyuAvailability>(JianyuAvailability.Uninitialized)
        val openGate = CompletableDeferred<Unit>()
        var initializeCalls = 0
        var fail = false
        val sessions = mutableListOf<FakeSession>()
        override suspend fun initialize(retry: Boolean) {
            initializeCalls++
            openGate.await()
            availability.value = if (fail) JianyuAvailability.Failed else JianyuAvailability.Ready(1)
        }
        override suspend fun openSession(generation: Long) = FakeSession(generation).also { sessions += it }
    }

    private class FakeSession(override val generation: Long) : JianyuHostSession {
        override val contentEpoch = "synthetic-$generation"
        override val services: JianyuServices get() = error("本测试不渲染业务页面")
        override val conversation: ConversationSession get() = error("本测试不创建实际对话")
        override val tasks = object : JianyuSessionTasks { override fun register(job: Job) = Unit }
        var task: Job? = null
        var releases = 0
        override suspend fun release() { releases++; task?.cancelAndJoin() }
        override fun releaseAsync() { releases++; task?.cancel() }
    }
}
