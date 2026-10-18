package com.elio.lifearchive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.preferences.PreferencesRepository
import com.elio.lifearchive.core.preferences.UserPreferences
import com.elio.lifearchive.domain.notes.NotesRepository
import com.elio.lifearchive.domain.notes.NoteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AppState(val preferences: UserPreferences = UserPreferences(), val ready: Boolean = false, val error: String? = null)

@HiltViewModel
internal class AppViewModel @Inject constructor(preferences: PreferencesRepository, notes: NotesRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                val initialized = notes.initialize()
                check(initialized is NoteResult.Success) { "笔记存储恢复失败：$initialized" }
                preferences.preferences.collect { mutableState.value = AppState(it, ready = true) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                mutableState.value = AppState(error = e.message ?: "存储初始化失败")
            }
        }
    }
}
