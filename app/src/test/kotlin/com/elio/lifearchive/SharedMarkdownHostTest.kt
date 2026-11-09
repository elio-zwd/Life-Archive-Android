package com.elio.lifearchive

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.designsystem.LifeArchiveTheme
import com.elio.lifearchive.core.markdown.MarkdownProtocol
import com.elio.lifearchive.core.markdown.MarkdownWebView
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedMarkdownHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun find(view: View): WebView? = if (view is WebView) view else (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
    }
    private fun request(path: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(MarkdownProtocol.ORIGIN + path)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
    private fun configuration(revision: String) = JSONObject().put("revision", revision).put("scale", 1).put("theme", "blue").toString()

    @Test fun independentContentProviderDoesNotRequireAnyNoteService() {
        compose.setContent {
            LifeArchiveTheme {
                MarkdownWebView(
                    documentId = "conversation/message", revision = "one", configuration = configuration("one"), initialOffset = 0,
                    onRequest = { incoming ->
                        if (incoming.url.path == "/reader/document") WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream("{\"html\":\"<p>独立内容</p>\"}".toByteArray())) else null
                    },
                    onAction = { null },
                )
            }
        }
        compose.waitForIdle()
        val view = find(compose.activity.window.decorView)!!
        val client = shadowOf(view).webViewClient
        assertEquals("conversation/message", Uri.parse(shadowOf(view).lastLoadedUrl).getQueryParameter("note"))
        val response = requireNotNull(client.shouldInterceptRequest(view, request("/reader/document")))
        assertTrue(response.data.bufferedReader().use { it.readText() }.contains("独立内容"))
        assertEquals(404, requireNotNull(client.shouldInterceptRequest(view, request("/skills/private.md"))).statusCode)
        assertEquals(404, requireNotNull(client.shouldInterceptRequest(view, request("/skill_knowledge/private.md"))).statusCode)
    }

    @Test fun switchingDocumentIdentityReleasesOldViewAndRevokesItsCallbacks() {
        var id by mutableStateOf("first")
        val actions = mutableListOf<String>()
        compose.setContent {
            LifeArchiveTheme {
                MarkdownWebView(id, "revision-$id", configuration("revision-$id"), 0,
                    onRequest = { null }, onAction = { actions += it.getQueryParameter("value").orEmpty(); null })
            }
        }
        compose.waitForIdle()
        val first = find(compose.activity.window.decorView)!!
        val oldClient = shadowOf(first).webViewClient
        compose.runOnIdle { id = "second" }
        compose.waitForIdle()
        val second = find(compose.activity.window.decorView)!!
        assertNotSame(first, second)
        assertNull(first.tag)
        assertEquals("second", Uri.parse(shadowOf(second).lastLoadedUrl).getQueryParameter("note"))
        compose.runOnIdle {
            oldClient.shouldOverrideUrlLoading(first, request("/reader/action?type=link&value=old&revision=revision-first"))
            assertTrue(actions.isEmpty())
            shadowOf(second).webViewClient.shouldOverrideUrlLoading(second, request("/reader/action?type=link&value=new&revision=revision-second"))
            assertEquals(listOf("new"), actions)
        }
    }
}
