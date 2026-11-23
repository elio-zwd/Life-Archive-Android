package com.elio.lifearchive

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.elio.jianyu.runtime.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal enum class Business { LIFE, JIANYU, PET }

internal sealed interface JianyuHostState {
    data object Idle : JianyuHostState
    data object Loading : JianyuHostState
    data object Maintenance : JianyuHostState
    data object Failed : JianyuHostState
    data class Ready(val session: JianyuHostSession, val uiOwner: ViewModelStoreOwner) : JianyuHostState
}

@HiltViewModel
internal class BusinessHostViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val runtimeProvider: Provider<JianyuHostRuntime>,
) : ViewModel() {
    private val selectedMutable = MutableStateFlow(Business.entries.find { it.name == savedState.get<String>(SELECTED) } ?: Business.LIFE)
    val selected = selectedMutable.asStateFlow()
    val companionChatRequest = savedState.getStateFlow("companion_chat_request", 0L)
    private val jianyuMutable = MutableStateFlow<JianyuHostState>(JianyuHostState.Idle)
    val jianyu = jianyuMutable.asStateFlow()
    private val lifeNoteMutable = MutableStateFlow<NoteDestination?>(null)
    val lifeNote = lifeNoteMutable.asStateFlow()
    private val runtime by lazy(LazyThreadSafetyMode.NONE) { runtimeProvider.get() }
    private var observer: Job? = null
    private var initialization: Job? = null
    private var ready: JianyuHostState.Ready? = null

    init { if (selected.value == Business.JIANYU) ensureJianyu() }

    fun select(business: Business) {
        savedState[SELECTED] = business.name
        selectedMutable.value = business
        if (business == Business.JIANYU) ensureJianyu()
    }

    fun retryJianyu() = ensureJianyu(retry = true)
    fun openCompanionChat() {
        savedState["companion_chat_request"] = companionChatRequest.value + 1
        select(Business.PET)
    }

    fun openLifeNote(destination: NoteDestination) {
        lifeNoteMutable.value = destination
        select(Business.LIFE)
    }

    fun consumeLifeNote(request: NoteDestination) {
        lifeNoteMutable.compareAndSet(request, null)
    }

    private fun ensureJianyu(retry: Boolean = false) {
        if (initialization?.isActive == true || (!retry && observer?.isActive == true)) return
        if (retry && runtime.availability.value is JianyuAvailability.Ready) {
            observer?.cancel()
            observer = null
        }
        jianyuMutable.value = JianyuHostState.Loading
        if (observer?.isActive != true) observer = viewModelScope.launch {
            runtime.availability.collect { availability ->
                when (availability) {
                    JianyuAvailability.Uninitialized -> Unit
                    JianyuAvailability.Failed -> {
                        jianyuMutable.value = JianyuHostState.Failed
                        releaseReady()
                    }
                    is JianyuAvailability.Maintenance -> {
                        jianyuMutable.value = JianyuHostState.Maintenance
                        releaseReady()
                    }
                    is JianyuAvailability.Ready -> {
                        if (ready?.session?.generation == availability.generation) {
                            jianyuMutable.value = requireNotNull(ready)
                        } else {
                            jianyuMutable.value = JianyuHostState.Loading
                            releaseReady()
                            try {
                                val session = runtime.openSession(availability.generation)
                                if (runtime.availability.value != availability) {
                                    session.release()
                                } else {
                                    val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                                    ready = JianyuHostState.Ready(session, owner)
                                    jianyuMutable.value = requireNotNull(ready)
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { reportFailure(error) }
                        }
                    }
                }
            }
        }
        initialization = viewModelScope.launch {
            try { runtime.initialize(retry) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportFailure(error) }
        }
    }

    private suspend fun releaseReady() {
        val old = ready ?: return
        ready = null
        old.uiOwner.viewModelStore.clear()
        old.session.release()
    }

    private fun reportFailure(error: Exception) {
        android.util.Log.e("JianyuHost", "见域初始化失败：${error.javaClass.simpleName}\n${error.stackTrace.take(8).joinToString("\n")}")
        jianyuMutable.value = JianyuHostState.Failed
    }

    override fun onCleared() {
        ready?.let {
            it.uiOwner.viewModelStore.clear()
            it.session.releaseAsync()
        }
        ready = null
    }

    private companion object { const val SELECTED = "host.selected-business" }
}
