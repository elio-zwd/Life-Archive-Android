package com.elio.lifearchive.feature.settings

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.backup.*
import com.elio.lifearchive.core.preferences.*
import com.elio.lifearchive.domain.notes.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fake(crossinline invoke: (String) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> invoke(method.name) } as T
    private fun cache() = fake<RemoteCacheRepository> { when (it) { "size" -> 0L; "clear" -> Unit; else -> error(it) } }
    private suspend fun SettingsViewModel.done() = withTimeout(10000) { state.first { !it.busy && it.message != null } }

    @Test fun validatedRestoreWaitsForConfirmationAndCancelDoesNotReplaceData() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val gate = StorageGate(); val preferences = PreferencesStore(File(temp.newFolder(), "settings.preferences_pb"), gate)
        var payload = "备份内容"; var initialized = 0
        val participant = object : BackupParticipant {
            override val id = "sample"
            override suspend fun snapshot(directory: File) { File(directory, "body").writeText(payload) }
            override suspend fun validate(directory: File) { check(File(directory, "body").isFile) }
            override suspend fun restore(directory: File) { payload = File(directory, "body").readText() }
        }
        val engine = BackupEngine(temp.newFolder(), gate, listOf(participant, preferences.backupParticipant))
        val notes = fake<NotesRepository> { when (it) { "initialize" -> { initialized++; NoteResult.Success(Unit) }; else -> error(it) } }
        val library = fake<LibraryRepository> { error(it) }
        val model = SettingsViewModel(preferences.repository, engine, notes, library, cache())
        try {
            val zip = ByteArrayOutputStream().also { engine.export(it) }.toByteArray(); payload = "当前内容"
            model.prepareRestore { zip.inputStream() }; model.done()
            assertNotNull(model.state.value.restore); assertEquals("当前内容", payload); assertEquals(0, initialized)
            model.cancelRestore(); model.done(); assertNull(model.state.value.restore); assertEquals("当前内容", payload)
            model.prepareRestore { zip.inputStream() }; model.done()
            var blockedReset = false; model.restore(beforeRestore = { false }) { blockedReset = true }; model.done()
            assertFalse(blockedReset); assertNotNull(model.state.value.restore); assertEquals("当前内容", payload); assertEquals(0, initialized)
            var reset = false; model.restore { reset = true }; model.done()
            assertEquals("备份内容", payload); assertTrue(reset); assertEquals(1, initialized)
        } finally { model.viewModelScope.cancel(); preferences.closeAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun committedRestoreResetsOldNavigationEvenWhenIndexInitializationFails() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val gate = StorageGate(); val preferences = PreferencesStore(File(temp.newFolder(), "settings.preferences_pb"), gate)
        val engine = BackupEngine(temp.newFolder(), gate, listOf(preferences.backupParticipant))
        val notes = fake<NotesRepository> { if (it == "initialize") NoteResult.StorageFailure("索引初始化失败") else error(it) }
        val model = SettingsViewModel(preferences.repository, engine, notes, fake<LibraryRepository> { error(it) }, cache())
        try {
            val zip = ByteArrayOutputStream().also { engine.export(it) }.toByteArray()
            model.prepareRestore { zip.inputStream() }; model.done()
            var reset = false; model.restore { reset = true }; val result = model.done()
            assertTrue(reset); assertNull(result.restore); assertTrue(result.message!!.contains("资料已替换"))
        } finally { model.viewModelScope.cancel(); preferences.closeAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun stagedImportRequiresExplicitCommitAndCanBeDiscarded() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val gate = StorageGate(); val preferences = PreferencesStore(File(temp.newFolder(), "settings.preferences_pb"), gate)
        val engine = BackupEngine(temp.newFolder(), gate, listOf(preferences.backupParticipant))
        var committed = 0; var discarded = 0
        val prepared = PreparedImport(UUID.randomUUID().toString(), "导入目录", 1, 0, emptyList())
        val library = fake<LibraryRepository> { when (it) { "prepareImport" -> NoteResult.Success(prepared); "commitImport" -> { committed++; NoteResult.Success(Unit) }; "discardImport" -> { discarded++; Unit }; else -> error(it) } }
        val model = SettingsViewModel(preferences.repository, engine, fake<NotesRepository> { error(it) }, library, cache())
        try {
            model.prepareImport(listOf(ImportEntry("记录.md") { "内容".byteInputStream() })); model.done()
            assertEquals(0, committed); assertNotNull(model.state.value.importing)
            model.cancelImport(); model.done(); assertEquals(1, discarded); assertEquals(0, committed)
            model.prepareImport(listOf(ImportEntry("记录.md") { "内容".byteInputStream() })); model.done(); model.commitImport(); model.done()
            assertEquals(1, committed); assertNull(model.state.value.importing)
        } finally { model.viewModelScope.cancel(); preferences.closeAndJoin(); Dispatchers.resetMain() }
    }
}
