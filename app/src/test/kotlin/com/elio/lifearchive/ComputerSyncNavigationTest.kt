package com.elio.lifearchive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LifeArchiveApplication::class, qualifiers = "w432dp-h960dp-mdpi")
class ComputerSyncNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun pairingFieldsAndUnpairedStateSurviveActivityRecreation() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("home-more").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("设置", substring = false).performClick()
        compose.onNodeWithText("电脑同步", substring = false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("立即同步", substring = false).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("电脑同步地址")).performScrollTo().performTextInput("http://192.168.1.8:3000/api/sync")
        compose.onNode(hasSetTextAction() and hasText("电脑显示的配对码")).performScrollTo().performTextInput("123456")
        compose.onNodeWithText("配对电脑", substring = false).performScrollTo().assertIsEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction() and hasText("http://192.168.1.8:3000/api/sync")).performScrollTo().assertExists()
        compose.onAllNodesWithText("123456", substring = false).assertCountEquals(0)
        compose.onNodeWithText("配对电脑", substring = false).performScrollTo().assertIsNotEnabled()
    }
}
