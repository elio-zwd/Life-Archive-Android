package com.elio.lifearchive.feature.notes

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.core.preferences.PreferencesRepository
import com.elio.lifearchive.domain.notes.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class OrganizationViewModelTest {
    @Suppress("UNCHECKED_CAST") private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args ?: emptyArray()) } as T
    private val summary = NoteSummary(NoteId("12345678-1234-1234-1234-123456789abc"), LibraryPath("职业发展/个人笔记/甲.md"), DocumentVersion("v1"), false)
    @Test fun singleNoteKeepsPlacementAndPreviewSurvivesUntilOneConditionalSubmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val note = LibraryNote(summary, false, 1000, 2000, null, emptyList(), null)
        var previews = 0; var applies = 0
        val library = proxy<LibraryRepository> { method, args -> when (method) {
            "organizationSuggestions" -> NoteResult.Success(listOf(OrganizationSuggestion(note, listOf("职业发展"), NotePlacement("职业发展", NoteKind.PERSONAL), "标准目录")))
            "query" -> NoteResult.Success(LibraryListing(listOf(note), emptyList(), emptyList(), 0))
            "previewOrganization" -> { previews++; @Suppress("UNCHECKED_CAST") val changes = args[0] as List<OrganizationChange>; NoteResult.Success(OrganizationPreview(changes.map { OrganizationPreviewItem(it, summary.path.value, summary.path.value, emptyList(), emptyList(), emptyList()) }, mapOf(summary.id to summary.version))) }
            "applyOrganization" -> { applies++; NoteResult.Success(OrganizationResult(listOf(OrganizationOutcome(summary.id, "COMPLETED", "完成")))) }
            else -> error(method)
        } }
        val model = OrganizationViewModel(library, setOf(summary.id))
        try {
            runCurrent(); assertEquals("职业发展", model.state.value.topic); assertEquals(NoteKind.PERSONAL, model.state.value.kind)
            assertFalse(model.state.value.confirmTimes); assertEquals("", model.state.value.created)
            model.preview(); model.preview(); runCurrent(); assertEquals(1, previews)
            val retained = model.state.value.preview; assertNotNull(retained)
            model.apply(); model.apply(); runCurrent(); assertEquals(1, applies)
            assertNull(model.state.value.preview); assertTrue(model.state.value.result!!.contains("完成 1"))
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun pastedUnknownTagsRequireExplicitDecisionBeforeCreatingOneNote() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val creates = mutableListOf<Pair<String, RawDocument>>()
        val notes = proxy<NotesRepository> { method, args -> when (method) {
            "observeNotes" -> MutableStateFlow(emptyList<NoteSummary>())
            "createManaged" -> { val path = args[0] as String; val raw = args[1] as RawDocument; creates += path to raw; assertEquals(false, args[2]); NoteResult.Committed(NoteSnapshot(summary.copy(path = LibraryPath(path)), raw), false) }
            else -> error(method)
        } }
        val library = proxy<LibraryRepository> { method, _ -> when (method) { "query" -> NoteResult.Success(LibraryListing(emptyList(), emptyList(), listOf("职业发展/面试"), 0)); else -> error(method) } }
        val preferences = proxy<PreferencesRepository> { method, _ -> error(method) }
        val model = VideoNotesViewModel(notes, preferences, "职业发展", true, library)
        try {
            runCurrent(); model.body("---\ntags: [新标签]\nunknown: keep\n---\n正文 #新标签"); model.name("甲"); model.kind(NoteKind.PERSONAL)
            model.create(); runCurrent(); assertTrue(creates.isEmpty()); assertEquals(listOf("新标签"), model.state.value.pendingTags)
            model.tagChoice("新标签", "职业发展/面试"); model.confirmTags(); model.create(); runCurrent()
            assertEquals(1, creates.size); assertEquals("职业发展/个人笔记/甲.md", creates.single().first)
            assertTrue(creates.single().second.text.contains("unknown: keep")); assertTrue(creates.single().second.text.contains("#职业发展/面试"))
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
