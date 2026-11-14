package com.elio.lifearchive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LifeArchiveApplication::class)
class BusinessHostUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun closeRuntime() {
        val context = compose.activity.applicationContext
        compose.activityRule.scenario.close()
        resetJianyuRuntimeForTest(context)
    }

    @Test fun realEntryAndUnsentInputSurviveSwitchingAndActivityRecreation() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("Life 冷启动不能打开见域数据库", compose.activity.getDatabasePath("jianyu_roundtable_database").exists())
        focusWindow()
        compose.onNodeWithTag("business-switch").assertIsDisplayed().assertContentDescriptionEquals("切换到见域").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("jianyu_app_content_root").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("见域暂时无法打开，请重试。").fetchSemanticsNodes().isNotEmpty()
        }
        val logs = ShadowLog.getLogsForTag("JianyuHost").joinToString("\n") { it.msg }
        check(compose.onAllNodesWithTag("jianyu_app_content_root").fetchSemanticsNodes().isNotEmpty()) { "见域未就绪：$logs" }
        compose.onNodeWithTag("jianyu_app_content_root").assertIsDisplayed()
        compose.onNodeWithTag("chat_input").performTextInput("尚未发送的输入，重建后仍应保留")
        focusWindow()
        compose.onNodeWithTag("business-switch").assertContentDescriptionEquals("切换到Life").performClick()
        compose.onNodeWithTag("new-note").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        focusWindow()
        compose.onNodeWithTag("business-switch").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_input").assertTextContains("尚未发送的输入，重建后仍应保留")
    }

    private fun focusWindow() {
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("business-switch").fetchSemanticsNodes().isNotEmpty() }
    }
}
