package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.elio.lifearchive.domain.pet.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w432dp-h960dp-mdpi")
class CompanionLayoutsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeStageOverlapsLowerPanelAndCanBeCollapsed() {
        compose.setContent {
            MaterialTheme { CompanionConversation(CompanionState(), "草稿", {}, {}, {}, {}, {}, avatar = { modifier, _ -> Box(modifier) }) }
        }
        val stage = compose.onNodeWithTag("pet-large-avatar").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("pet-dialogue-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("大形象不沿用旧 180dp 上限", stage.height > 300f)
        assertTrue("对话面板叠在形象下缘", panel.top < stage.bottom)
        compose.onNodeWithTag("pet-input").assertIsDisplayed()
        compose.onNodeWithTag("pet-send").assertIsDisplayed()
        compose.onNodeWithTag("pet-avatar-toggle").performClick()
        compose.onNodeWithTag("pet-large-avatar").assertDoesNotExist()
        compose.onNodeWithTag("pet-input").assertIsDisplayed()
    }

    @Test @Config(sdk = [34], qualifiers = "w320dp-h480dp-mdpi")
    fun shortWindowAndLargeFontKeepTheComposerReachable() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    CompanionConversation(CompanionState(), "中文草稿", {}, {}, {}, {}, {}, avatar = { modifier, _ -> Box(modifier) })
                }
            }
        }
        compose.onNodeWithTag("pet-input").assertIsDisplayed()
        compose.onNodeWithTag("pet-send").assertIsDisplayed()
    }

    @Test fun settingsCardsKeepIndependentControlsWithLongProfile() {
        var visible = false
        var opens = 0
        var stops = 0
        var edits = 0
        compose.setContent {
            MaterialTheme {
                CompanionSettings(CompanionState(profile = CompanionProfile("可修改的很长的伙伴名称".repeat(3), "认真倾听。".repeat(200))),
                    { edits++ }, { visible = it }, { opens++ }, { stops++ }, {}, avatar = { Box(it) })
            }
        }
        compose.onNodeWithTag("pet-profile-edit").performScrollTo().performClick()
        compose.onNodeWithTag("pet-in-app-visible").performScrollTo().performClick()
        compose.onNodeWithText("开启桌面陪伴").performScrollTo().performClick()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        assertEquals(1, edits); assertTrue(visible); assertEquals(1, opens); assertEquals(1, stops)
    }

    @Test fun profileSheetRestoresUnsubmittedTextAndKeepsFailedSaveOpen() {
        val restoration = StateRestorationTester(compose)
        var opened by mutableStateOf(true)
        var succeeds = false
        var saved = ""
        restoration.setContent {
            MaterialTheme {
                if (opened) ProfileEditorSheet(CompanionProfile(), { opened = false }) { name, personality ->
                    saved = "$name|$personality"; succeeds
                }
            }
        }
        compose.onNodeWithTag("pet-profile-name").performTextClearance()
        compose.onNodeWithTag("pet-profile-name").performTextInput("小日和")
        compose.onNodeWithTag("pet-profile-personality").performTextClearance()
        compose.onNodeWithTag("pet-profile-personality").performTextInput("温和，也会表达自己的想法。")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pet-profile-name").assertTextContains("小日和")
        compose.onNodeWithTag("pet-editor-save").performClick()
        compose.onNodeWithTag("pet-editor-sheet").assertExists()
        assertTrue(opened)
        succeeds = true
        compose.onNodeWithTag("pet-editor-save").performClick()
        compose.waitUntil { !opened }
        assertEquals("小日和|温和，也会表达自己的想法。", saved)
    }

    @Test fun memorySheetCannotSaveBlankAndCancelDoesNotSubmit() {
        var text by mutableStateOf("")
        var saves = 0
        var dismissed = false
        compose.setContent {
            MaterialTheme { MemoryEditorSheet(text, { text = it }, true, true, { dismissed = true }) { saves++; true } }
        }
        compose.onNodeWithText("来自共同对话").assertIsDisplayed()
        compose.onNodeWithTag("pet-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("pet-memory-text").performTextInput("只修改这条记忆")
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed); assertEquals(0, saves)
    }

    @Test fun memorySheetDisplaysSpecificErrorAndClipsOverlongPaste() {
        var text by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                MemoryEditorSheet(text, { text = it }, false, false, {}, errorMessage = "记忆已达 500 条，请先整理。") { false }
            }
        }
        compose.onNodeWithTag("pet-memory-text").performTextInput("字".repeat(2020))
        assertEquals(2000, text.length)
        compose.onNodeWithTag("pet-editor-save").performClick()
        compose.onNodeWithTag("pet-editor-error").assertTextEquals("记忆已达 500 条，请先整理。")
    }

    @Test @Config(sdk = [34], qualifiers = "w320dp-h240dp-mdpi")
    fun autoHiddenShortWindowHidesAvatarToggleButton() {
        compose.setContent {
            MaterialTheme { CompanionConversation(CompanionState(), "", {}, {}, {}, {}, {}, avatar = { modifier, _ -> Box(modifier) }) }
        }
        compose.onNodeWithTag("pet-large-avatar").assertDoesNotExist()
        compose.onNodeWithTag("pet-avatar-toggle").assertDoesNotExist()
    }
}
