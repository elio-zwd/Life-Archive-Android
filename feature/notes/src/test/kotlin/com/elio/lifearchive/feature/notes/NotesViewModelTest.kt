package com.elio.lifearchive.feature.notes

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.*
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotesViewModelTest {
    private class FakeNotes : NotesRepository {
        val updates = kotlinx.coroutines.flow.MutableSharedFlow<List<NoteSummary>>(replay = 1).apply { tryEmit(emptyList()) }
        var creates = 0
        var result: NoteResult<NoteSnapshot> = NoteResult.PathConflict
        override fun observeNotes() = updates
        override suspend fun initialize() = NoteResult.Success(Unit)
        override suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot> { creates++; return result }
        override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> = error("unused")
        override suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long?): NoteResult<NoteSnapshot> = error("unused")
        override suspend fun readDraft(id: NoteId): NoteResult<Draft?> = error("unused")
        override suspend fun writeDraft(draft: Draft): NoteResult<Unit> = error("unused")
        override suspend fun readReadingOffset(id: NoteId): NoteResult<Int> = error("unused")
        override suspend fun updateReadingOffset(id: NoteId, offset: Int): NoteResult<Unit> = error("unused")
        override suspend fun rebuildIndex(): NoteResult<Unit> = error("unused")
        override suspend fun importAttachment(path: String, input: InputStream): NoteResult<LibraryPath> = error("unused")
        override suspend fun copyAttachment(path: LibraryPath, output: OutputStream): NoteResult<Unit> = error("unused")
    }

    @Test fun repeatedStorageNotificationsCancelObsoleteQueries() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var active = 0
        var maximum = 0
        var cancelled = 0
        val pending = mutableListOf<kotlinx.coroutines.CancellableContinuation<NoteResult<LibraryListing>>>()
        val query: suspend (NotesQuery) -> NoteResult<LibraryListing> = {
            suspendCancellableCoroutine { continuation ->
                active++
                maximum = maxOf(maximum, active)
                pending += continuation
                continuation.invokeOnCancellation { active--; cancelled++ }
            }
        }
        @Suppress("UNCHECKED_CAST")
        val library = java.lang.reflect.Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getProgress" -> flowOf(null)
                "query" -> {
                    query.startCoroutine(args!![0] as NotesQuery, args.last() as kotlin.coroutines.Continuation<NoteResult<LibraryListing>>)
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                }
                else -> error(method.name)
            }
        } as LibraryRepository
        val repository = FakeNotes()
        val model = NotesViewModel(repository, library)
        try {
            runCurrent()
            repeat(5) {
                repository.updates.emit(emptyList())
                runCurrent()
            }
            assertEquals(1, active)
            assertEquals(1, maximum)
            assertEquals(5, cancelled)
            pending.last().resumeWith(Result.success(NoteResult.Success(LibraryListing(emptyList(), emptyList(), emptyList(), 0))))
            runCurrent()
            assertFalse(model.state.value.updating)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun repositoryNotificationsNeverShowUnfilteredRowsAndOldQueryReceiptsAreIgnored() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        data class Pending(val query: NotesQuery, val continuation: kotlin.coroutines.Continuation<NoteResult<LibraryListing>>)
        val pending = mutableListOf<Pending>()
        @Suppress("UNCHECKED_CAST")
        val library = java.lang.reflect.Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getProgress" -> flowOf(null)
                "query" -> { pending += Pending(args!![0] as NotesQuery, args.last() as kotlin.coroutines.Continuation<NoteResult<LibraryListing>>); kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED }
                else -> error(method.name)
            }
        } as LibraryRepository
        fun listing(name: String) = LibraryListing(listOf(LibraryNote(NoteSummary(NoteId(name), LibraryPath("$name.md"), DocumentVersion("v"), false), false, 0, 0, null, emptyList(), null)), emptyList(), emptyList(), 0)
        fun complete(index: Int, result: NoteResult<LibraryListing>) { pending[index].continuation.resumeWith(Result.success(result)) }
        val repo = FakeNotes(); val model = NotesViewModel(repo, library)
        try {
            runCurrent(); complete(0, NoteResult.Success(listing("初始"))); runCurrent()
            model.query(NotesQuery()); advanceTimeBy(200); runCurrent()
            assertEquals(1, pending.size); assertFalse(model.state.value.updating)
            model.query(NotesQuery(folder = "选中")); advanceTimeBy(200); runCurrent()
            complete(1, NoteResult.Success(listing("筛选"))); runCurrent()
            repo.updates.emit(listOf(listing("不应出现").notes.single().summary)); runCurrent()
            assertEquals("筛选", model.state.value.notes.single().id.value)
            model.query(NotesQuery(text = "新查询")); advanceTimeBy(200); runCurrent()
            complete(3, NoteResult.Success(listing("最新"))); runCurrent()
            complete(2, NoteResult.StorageFailure("旧错误")); runCurrent()
            assertNull(model.state.value.error); assertEquals("最新", model.state.value.notes.single().id.value)
            model.query(NotesQuery(text = "A")); advanceTimeBy(200); runCurrent()
            model.query(NotesQuery(text = "B")); advanceTimeBy(200); runCurrent()
            model.query(NotesQuery(text = "A")); advanceTimeBy(200); runCurrent()
            complete(6, NoteResult.Success(listing("最新A"))); runCurrent()
            complete(4, NoteResult.Success(listing("旧A"))); complete(5, NoteResult.StorageFailure("旧B")); runCurrent()
            assertEquals("最新A", model.state.value.notes.single().id.value); assertFalse(model.state.value.updating)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun noCreateUntilConfirmationAndFailuresDoNotNavigate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeNotes()
        val model = NotesViewModel(repo)
        try {
            runCurrent()
            assertEquals(0, repo.creates)
            var navigated = false
            for (name in listOf("", "非法/路径", "CON", "尾随点.", "尾随空格 ", "a:b")) model.create(name) { navigated = true }
            assertEquals(0, repo.creates)
            model.create("重名") { navigated = true }
            runCurrent()
            assertFalse(navigated)
            assertTrue(model.state.value.error!!.contains("同名"))
            val note = NoteSnapshot(NoteSummary(NoteId("new"), LibraryPath("新笔记.md"), DocumentVersion("v1"), false), RawDocument(""))
            repo.result = NoteResult.Committed(note, false)
            model.create("新笔记") { navigated = true }
            runCurrent()
            assertTrue(navigated)
            assertNull(model.state.value.error)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun storageChangesInvalidateImmediatelyEvenWhenAnOlderQueryIsStillRunning() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = mutableListOf<kotlin.coroutines.Continuation<NoteResult<LibraryListing>>>()
        @Suppress("UNCHECKED_CAST")
        val library = java.lang.reflect.Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getProgress" -> flowOf(null)
                "query" -> { pending += args!!.last() as kotlin.coroutines.Continuation<NoteResult<LibraryListing>>; kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED }
                else -> error(method.name)
            }
        } as LibraryRepository
        fun summary(version: String) = NoteSummary(NoteId("one"), LibraryPath("one.md"), DocumentVersion(version), false)
        fun listing(version: String) = LibraryListing(listOf(LibraryNote(summary(version), false, 0, 0, null, emptyList(), null)), emptyList(), emptyList(), 0)
        fun complete(index: Int, version: String) { pending[index].resumeWith(Result.success(NoteResult.Success(listing(version)))) }
        val repository = FakeNotes()
        val model = NotesViewModel(repository, library)
        try {
            runCurrent()
            complete(0, "v0"); runCurrent()
            repository.updates.emit(listOf(summary("v1"))); runCurrent()
            assertEquals(1L, model.state.value.thumbnailEpoch)
            repository.updates.emit(listOf(summary("v2"))); runCurrent()
            assertEquals(2L, model.state.value.thumbnailEpoch)
            assertEquals(3, pending.size)
            complete(1, "v1"); runCurrent()
            assertEquals(DocumentVersion("v0"), model.state.value.notes.single().version)
            assertTrue(model.state.value.updating)
            complete(2, "v2"); runCurrent()
            assertEquals(DocumentVersion("v2"), model.state.value.notes.single().version)
            assertFalse(model.state.value.updating)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
