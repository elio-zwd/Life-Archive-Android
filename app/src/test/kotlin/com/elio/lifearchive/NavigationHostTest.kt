package com.elio.lifearchive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LifeArchiveApplication::class)
class NavigationHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun backStackSurvivesActivityRecreationAndReturnsHome() {
        compose.waitUntil(15000) {
            compose.onAllNodes(hasText("打开底层接入示例")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("打开底层接入示例").performClick()
        compose.onNodeWithText("示例使用独立资料库").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("示例使用独立资料库").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("打开底层接入示例").assertIsDisplayed()
    }
}
