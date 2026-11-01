package com.elio.lifearchive.feature.editor

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.designsystem.DocumentPageTheme
import com.elio.lifearchive.core.designsystem.DocumentPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w412dp-h892dp-mdpi")
class SourceLinkCompositionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun composingChineseNeverReadsClipboardOrReplacesTheInput() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repo = MemoryNotes()
        val model = EditorViewModel(repo, repo.note.summary.id, true)
        try {
            val session = model.session!!
            lateinit var view: View
            compose.setContent {
                val current = LocalView.current
                SideEffect { view = current }
                DocumentPageTheme(DocumentPalette.BLUE) { ComposeEditorEngine.Content(session, EditorMode.SOURCE, null, 1f, true, true) }
            }
            compose.onNodeWithTag("editor-body").performClick()
            compose.runOnIdle {
                val connection = checkNotNull(view.onCreateInputConnection(EditorInfo()))
                connection.setComposingText("中文候选", 1)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNotNull(session.text.composition)
                val before = session.content.source
                var reads = 0
                model.openSourceLink { reads++; "https://example.test/v" }
                assertEquals(0, reads)
                assertNull(model.state.value.sourceLink)
                assertEquals(before, session.content.source)
                assertTrue(model.state.value.message!!.contains("完成当前输入"))
            }
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
