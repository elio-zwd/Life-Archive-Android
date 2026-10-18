package com.elio.lifearchive

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.preferences.PreferencesRepository
import com.elio.lifearchive.core.preferences.ThemeMode
import com.elio.lifearchive.core.preferences.UserPreferences
import com.elio.lifearchive.domain.notes.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private class FakePreferences : PreferencesRepository {
        override val preferences = MutableStateFlow(UserPreferences())
        override suspend fun setTheme(theme: ThemeMode) { preferences.value = preferences.value.copy(theme = theme) }
        override suspend fun setFontScale(scale: Float) { preferences.value = preferences.value.copy(fontScale = scale) }
    }

    private class FakeNotes(val initialization: NoteResult<Unit>) : NotesRepository {
        override suspend fun initialize() = initialization
        override fun observeNotes() = flowOf(emptyList<NoteSummary>())
        override suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot> = error("本测试不调用")
        override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> = error("本测试不调用")
        override suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long?): NoteResult<NoteSnapshot> = error("本测试不调用")
        override suspend fun readDraft(id: NoteId): NoteResult<Draft?> = error("本测试不调用")
        override suspend fun writeDraft(draft: Draft): NoteResult<Unit> = error("本测试不调用")
        override suspend fun rebuildIndex(): NoteResult<Unit> = error("本测试不调用")
        override suspend fun importAttachment(path: String, input: InputStream): NoteResult<LibraryPath> = error("本测试不调用")
        override suspend fun copyAttachment(path: LibraryPath, output: OutputStream): NoteResult<Unit> = error("本测试不调用")
    }

    @Test fun fakeRepositoriesDriveTheSameApplicationState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = FakePreferences()
        val model = AppViewModel(preferences, FakeNotes(NoteResult.Success(Unit)))
        try {
            runCurrent()
            assertTrue(model.state.value.ready)
            preferences.setTheme(ThemeMode.DARK)
            runCurrent()
            assertEquals(ThemeMode.DARK, model.state.value.preferences.theme)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun failedRecoveryDoesNotReportReady() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = AppViewModel(FakePreferences(), FakeNotes(NoteResult.StorageFailure("需要恢复", true)))
        try {
            runCurrent()
            assertFalse(model.state.value.ready)
            assertNotNull(model.state.value.error)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
