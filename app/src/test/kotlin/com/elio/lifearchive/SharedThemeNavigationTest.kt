package com.elio.lifearchive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.designsystem.colors
import com.elio.lifearchive.core.designsystem.label
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.preferences.DocumentTheme
import com.elio.lifearchive.domain.notes.NoteResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LifeArchiveApplication::class, qualifiers = "w432dp-h960dp-mdpi")
class SharedThemeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun home() = compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("home-more")).fetchSemanticsNodes().isNotEmpty() }

    private fun select(palette: DocumentPalette) {
        compose.onNodeWithText("首页、阅读与编辑共用一个主题。").assertIsDisplayed()
        compose.onAllNodesWithText("Aa").assertCountEquals(3)
        compose.onNodeWithText(palette.label, substring = false).performClick()
        val expected = DocumentTheme.valueOf(palette.name)
        compose.waitUntil(15000) { runBlocking { compose.activity.preferences.preferences.first().documentTheme == expected } }
        compose.waitForIdle()
    }

    private fun color(text: String, expected: Color) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = false).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(expected, results.single().layoutInput.style.color)
    }

    @Test fun allThreeStylesShareOneChoiceAcrossHomeReaderSettingsAndRecreation() {
        home()
        for (palette in DocumentPalette.entries) {
            compose.onNodeWithTag("home-more").performClick()
            compose.onNode(hasText("主题：", substring = true)).performClick()
            select(palette)
            color("Life Archive", palette.colors.ink)
        }

        val title = "共享主题-${java.util.UUID.randomUUID().toString().take(6)}"
        val note = runBlocking { (compose.activity.notes.create("$title.md", RawDocument("# 共享主题\n\n**关键词**与==原理==")) as NoteResult.Committed).value }
        val tag = "note:${note.summary.id.value}"
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithContentDescription("更多", useUnmergedTree = true).performClick()
        compose.onNodeWithText("主题", substring = false).performClick()
        select(DocumentPalette.WARM)
        compose.onNodeWithContentDescription("返回").performClick()
        home()
        color("Life Archive", DocumentPalette.WARM.colors.ink)

        compose.onNodeWithText("设置", substring = false).performClick()
        compose.onNodeWithText("主题：B · 暖纸色", substring = false).performScrollTo().performClick()
        select(DocumentPalette.GRAPHITE)
        compose.onAllNodesWithText("跟随系统", substring = false).assertCountEquals(0)
        compose.onNodeWithText("返回", substring = false).performClick()
        home()
        color("Life Archive", DocumentPalette.GRAPHITE.colors.ink)
        compose.activityRule.scenario.recreate()
        home()
        color("Life Archive", DocumentPalette.GRAPHITE.colors.ink)
    }
}
