package com.elio.lifearchive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootstrapSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startupShowsApplicationNameAndCompletionMessage() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.app_name))
            .assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("全部笔记")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("全部笔记")
            .assertIsDisplayed()
    }
}
