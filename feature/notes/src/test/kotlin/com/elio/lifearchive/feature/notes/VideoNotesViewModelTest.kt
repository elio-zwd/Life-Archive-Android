package com.elio.lifearchive.feature.notes

import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.core.preferences.*
import com.elio.lifearchive.domain.notes.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoNotesViewModelTest {
    private class FakePreferences : PreferencesRepository {
        val saved = MutableStateFlow(UserPreferences())
        var readFailure = false
        var writeFailure = false
        override val preferences = flow { if (readFailure) error("读取故障"); emit(saved.value) }
        override suspend fun setTheme(theme: ThemeMode) = Unit
        override suspend fun setFontScale(scale: Float) = Unit
        override suspend fun setVideoPrompt(type: VideoPromptType, text: String?) {
            if (writeFailure) error("写入故障")
            saved.value = saved.value.copy(videoPrompts = if (text == null) saved.value.videoPrompts - type else saved.value.videoPrompts + (type to text))
        }
    }

    private class FakeNotes : NotesRepository {
        val creates = mutableListOf<Pair<String, RawDocument>>()
        var waiting: CompletableDeferred<Unit>? = null
        var result: NoteResult<NoteSnapshot> = NoteResult.PathConflict
        var throwing = false
        override fun observeNotes() = MutableStateFlow(emptyList<NoteSummary>())
        override suspend fun initialize() = NoteResult.Success(Unit)
        override suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot> {
            creates += path to document
            waiting?.await()
            if (throwing) error("异常故障")
            return result
        }
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

    @Test fun captureKeepsRawBodyAndSourceAndBlocksDuplicateCreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeNotes().apply { waiting = CompletableDeferred() }
        val model = VideoNotesViewModel(repo, FakePreferences(), "技巧/手机")
        try {
            runCurrent()
            val raw = "---\r\ncustom: unchanged\r\n---\r\n# 独立正文标题\r\n\r\n普通文本 **强调** 😀\r\n"
            val source = "分享原文\nhttps://example.test/video?a=1&b=2"
            model.name("文件名"); model.body(raw); model.source(source)
            model.create(); model.create(); runCurrent()
            assertEquals(listOf("技巧/手机/文件名.md" to RawDocument(raw + "\r\n\r\n## 来源\r\n" + source)), repo.creates)
            model.body("保存中不能替换"); model.paste("另一篇"); model.name("另一个名字")
            var left = false
            model.leave { left = true }
            assertFalse(left); assertEquals(raw, model.state.value.body)
            val note = NoteSnapshot(NoteSummary(NoteId("new"), LibraryPath("技巧/手机/文件名.md"), DocumentVersion("v1"), false), repo.creates.single().second)
            repo.result = NoteResult.Committed(note, false)
            repo.waiting!!.complete(Unit); runCurrent()
            assertEquals(note.summary.id, model.state.value.createdNote)
            model.create(); runCurrent(); assertEquals(1, repo.creates.size)
            model.noteOpened()
            assertEquals("", model.state.value.body); assertFalse(model.state.value.hasUnsavedInput)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun invalidNamesAndFailuresKeepInputsAndCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeNotes(); val model = VideoNotesViewModel(repo, FakePreferences(), null)
        try {
            runCurrent(); model.body("无固定格式的一段总结"); model.source("来源原文")
            for (name in listOf("", "非法/路径", "非法\\路径", "CON", "尾随点.", "尾随空格 ", "a:b")) {
                model.name(name); model.create(); runCurrent()
            }
            assertTrue(repo.creates.isEmpty())
            model.name("合法文件名")
            for (result in listOf(NoteResult.PathConflict, NoteResult.StorageFailure("存储失败"))) {
                repo.result = result; model.create(); runCurrent()
                assertNull(model.state.value.createdNote); assertFalse(model.state.value.busy)
                assertEquals("合法文件名", model.state.value.name); assertEquals("无固定格式的一段总结", model.state.value.body)
                assertEquals("来源原文", model.state.value.source); assertNotNull(model.state.value.error)
            }
            repo.throwing = true; model.create(); runCurrent()
            assertTrue(model.state.value.error!!.contains("异常故障")); assertFalse(model.state.value.busy)
            assertEquals("合法文件名.md", repo.creates.last().first)
            assertEquals("首尾不变 \r\n", videoSummaryBody("首尾不变 \r\n", " \n"))
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun pasteAndLeaveRequireExplicitConfirmationAndKeepOtherFields() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = VideoNotesViewModel(FakeNotes(), FakePreferences(), null)
        try {
            runCurrent()
            var left = false; model.leave { left = true }; assertTrue(left)
            model.name("名称"); model.source("来源")
            model.paste("原正文\n第二行"); model.paste(null)
            assertEquals("原正文\n第二行", model.state.value.body)
            assertNotNull(model.state.value.error)
            model.paste("新总结"); assertEquals("新总结", model.state.value.pendingPaste)
            model.confirmPaste(false); assertEquals("原正文\n第二行", model.state.value.body)
            model.paste("新总结"); model.confirmPaste(true)
            assertEquals("新总结", model.state.value.body)
            assertEquals("名称", model.state.value.name); assertEquals("来源", model.state.value.source)
            left = false; model.leave { left = true }
            assertFalse(left); assertTrue(model.state.value.confirmLeave)
            model.cancelLeave(); assertFalse(model.state.value.confirmLeave)
            model.tab(0); model.select(VideoPromptType.HOW_TO); model.prompt("尚未保存的提示词")
            model.tab(1); model.tab(0)
            assertEquals("尚未保存的提示词", model.state.value.prompt)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun promptFailuresKeepDraftAndRestoreAffectsOnlySelectedType() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = FakePreferences(); val model = VideoNotesViewModel(FakeNotes(), prefs, null)
        try {
            runCurrent(); model.select(VideoPromptType.HOW_TO); model.prompt("技巧自定义\n参数不能漏")
            prefs.writeFailure = true; model.savePrompt(); runCurrent()
            assertEquals("技巧自定义\n参数不能漏", model.state.value.prompt)
            assertTrue(prefs.saved.value.videoPrompts.isEmpty()); assertTrue(model.state.value.hasUnsavedInput)
            assertNotNull(model.state.value.error); assertFalse(model.state.value.busy)
            prefs.writeFailure = false; model.savePrompt(); runCurrent()
            assertEquals("技巧自定义\n参数不能漏", prefs.saved.value.videoPrompts[VideoPromptType.HOW_TO])
            assertFalse(model.state.value.hasUnsavedInput)
            model.select(VideoPromptType.GENERAL); model.prompt("通用未保存修改")
            model.select(VideoPromptType.HOW_TO); model.savePrompt(restoreDefault = true); runCurrent()
            assertEquals(VideoPromptType.HOW_TO.defaultPrompt, model.state.value.prompt)
            assertFalse(prefs.saved.value.videoPrompts.containsKey(VideoPromptType.HOW_TO))
            model.select(VideoPromptType.GENERAL); assertEquals("通用未保存修改", model.state.value.prompt)
            assertTrue(model.state.value.hasUnsavedInput)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun promptReadCanRetryWithoutDiscardingIndependentCaptureForm() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = FakePreferences().apply { readFailure = true }
        val model = VideoNotesViewModel(FakeNotes(), prefs, null)
        try {
            runCurrent(); assertFalse(model.state.value.promptsLoaded)
            model.tab(1); model.name("独立收录"); model.body("普通内容")
            prefs.readFailure = false
            prefs.saved.value = UserPreferences(videoPrompts = mapOf(VideoPromptType.CHOOSING to "持久化的避坑提示词"))
            model.loadPrompts(); runCurrent()
            assertTrue(model.state.value.promptsLoaded); assertEquals("普通内容", model.state.value.body)
            model.select(VideoPromptType.CHOOSING); assertEquals("持久化的避坑提示词", model.state.value.prompt)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
