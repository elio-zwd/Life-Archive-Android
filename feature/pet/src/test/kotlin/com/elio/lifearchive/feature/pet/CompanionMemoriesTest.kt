package com.elio.lifearchive.feature.pet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.elio.lifearchive.domain.pet.CompanionMemory
import com.elio.lifearchive.domain.pet.CompanionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
class CompanionMemoriesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoColumnsKeepSourceAndActionsBoundToTheRightMemory() {
        val manual = CompanionMemory("manual", "周末喜欢散步", null, 1)
        val conversation = CompanionMemory("chat", "喜欢安静的交流方式", "message-1", 3)
        var edited: CompanionMemory? = null
        var deleted: CompanionMemory? = null
        compose.setContent {
            MaterialTheme {
                CompanionMemories(CompanionState(memories = listOf(manual, conversation)), {}, { edited = it }, { deleted = it })
            }
        }
        val first = compose.onNodeWithTag("pet-memory-manual").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("pet-memory-chat").fetchSemanticsNode().boundsInRoot
        assertTrue("普通字号应使用双列手记", first.right < second.left)
        compose.onNodeWithText("手动添加").assertIsDisplayed()
        compose.onNodeWithText("来自共同对话").assertIsDisplayed()
        compose.onNodeWithTag("pet-memory-edit-chat").performClick()
        compose.onNodeWithTag("pet-memory-delete-manual").performClick()
        assertEquals(conversation, edited)
        assertEquals(manual, deleted)
    }

    @Test fun largeFontUsesOneColumnAndLongMemoryCanReachItsActions() {
        val longText = "忙的时候，希望先听我说完。".repeat(150)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    CompanionMemories(CompanionState(memories = listOf(
                        CompanionMemory("long", longText, null, 0),
                        CompanionMemory("last", "最后一条记忆", null, 0),
                    )), {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("pet-memory-add").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pet-memory-delete-long").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(longText).assertExists()
        compose.onNodeWithTag("pet-memory-delete-last").performScrollTo().assertIsDisplayed()
        val card = compose.onNodeWithTag("pet-memory-last").fetchSemanticsNode().boundsInRoot
        assertTrue("大字体时卡片应独占可用宽度", card.width > 300f)
    }

    @Test fun emptyNotebookExplainsHowToStartAndCanAdd() {
        var adds = 0
        compose.setContent { MaterialTheme { CompanionMemories(CompanionState(), { adds++ }, {}, {}) } }
        compose.onNodeWithText("把第一件小事留在这里").assertIsDisplayed()
        compose.onNodeWithTag("pet-memory-add").performClick()
        assertEquals(1, adds)
    }
}
