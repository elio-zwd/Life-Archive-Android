package com.elio.jianyu.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.elio.jianyu.ui.components.SharedMarkdownText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-mdpi")
class MarkdownListWindowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun hundredMessagesOnlyMountVisibleRenderers() = verifyWindow(100)
    @Test fun fiveHundredMessagesRecycleOldRenderers() = verifyWindow(500)

    private fun verifyWindow(count: Int) {
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.testTag("messages")) {
                    items((0 until count).toList(), key = { it }) { index ->
                        SharedMarkdownText("# 角色 $index\n\n正文、**强调**与 `代码`。", Modifier.height(120.dp))
                    }
                }
            }
        }
        var first = emptyList<WebView>()
        compose.runOnIdle {
            first = webViews(compose.activity.window.decorView)
            assertTrue("只能挂载可见项附近的 WebView，实际 ${first.size}", first.size in 1..16)
        }
        compose.onNodeWithTag("messages").performScrollToIndex(count - 1)
        compose.runOnIdle {
            val last = webViews(compose.activity.window.decorView)
            assertTrue(last.size in 1..16)
            assertTrue("屏外消息不能一直挂载渲染实例", last.none { it in first })
        }
    }

    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
