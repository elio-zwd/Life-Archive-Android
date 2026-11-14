package com.elio.jianyu.ui.screens.mine

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.elio.jianyu.backup.JianyuCompletePreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteBackupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preview() {
        val item = mutableStateOf(JianyuCompletePreview("one", "com.elio.jianyu", "synthetic", 1,
            mapOf("chat_sessions" to 2L), 1, 20, emptyList(), targetHasData = true))
        val busy = mutableStateOf(false)
        var restored = 0
        compose.setContent { MaterialTheme { CompleteRestorePreview(item.value, busy.value, {}, {}, { restored++ }) } }
        compose.onNodeWithTag("complete-restore-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("complete-restore-acknowledge").performScrollTo().performClick()
        compose.onNodeWithTag("complete-restore-confirm").assertIsNotEnabled()
        compose.runOnIdle { item.value = item.value.copy(targetBackupReady = true) }
        compose.onNodeWithTag("complete-restore-confirm").assertIsEnabled().performClick()
        assertEquals(1, restored)
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("complete-restore-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("complete-restore-acknowledge").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false; item.value = item.value.copy(token = "two") }
        compose.onNodeWithTag("complete-restore-confirm").assertIsNotEnabled()
        assertEquals(1, restored)
    }

    @Test fun password() {
        var confirmed: String? = null
        compose.setContent { MaterialTheme { CompletePasswordDialog(true, {}, { confirmed = it }) } }
        compose.onNodeWithTag("complete-password-submit").assertIsNotEnabled()
        compose.onNodeWithTag("complete-password").performTextInput("合成密码123456")
        compose.onNodeWithTag("complete-password-submit").assertIsNotEnabled()
        compose.onNodeWithTag("complete-password-confirm").performTextInput("合成密码123456")
        compose.onNodeWithTag("complete-password-submit").assertIsEnabled().performClick()
        assertEquals("合成密码123456", confirmed)
    }
}
