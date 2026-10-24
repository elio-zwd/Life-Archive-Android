package com.elio.lifearchive.feature.notes

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
}
