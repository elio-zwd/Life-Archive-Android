package com.elio.lifearchive

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elio.lifearchive.core.designsystem.LifeArchiveTheme
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.feature.editor.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class ReaderHostStabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args -> when (method.name) { "equals" -> proxy === args?.firstOrNull(); "hashCode" -> System.identityHashCode(proxy); "toString" -> T::class.java.simpleName; else -> error("不应调用 ${method.name}") } } as T
    private fun find(view: View): WebView? = if (view is WebView) view else (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
    private fun request(revision: String, type: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse("https://appassets.androidplatform.net/reader/action?type=$type&value=目标&revision=$revision")
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun scrollAndConfigurationUpdatesDoNotReloadTheWebViewAndStaleCallbacksAreIgnored() {
        var document by mutableStateOf(ReaderSnapshot("正文", "first"))
        var offset by mutableIntStateOf(10)
        var scale by mutableFloatStateOf(1f)
        val links = mutableListOf<String>()
        val services = ReaderServices(NoteId("root"), unused(), unused(), unused(), false, {})
        compose.setContent { LifeArchiveTheme { WebReaderRenderer.Content(document, scale, offset, {}, { null }, { links += it }, services) } }
        compose.waitForIdle()
        val view = find(compose.activity.window.decorView)!!
        val shadow = shadowOf(view)
        val initialUrl = shadow.lastLoadedUrl
        assertTrue(initialUrl.contains("offset=10")); assertEquals(0f, view.alpha, 0f)
        compose.runOnIdle { offset = 88; scale = 1.2f }
        compose.waitForIdle()
        assertSame(view, find(compose.activity.window.decorView)); assertEquals(initialUrl, shadow.lastLoadedUrl)
        assertTrue(shadow.lastEvaluatedJavascript.contains("readerUpdate")); assertTrue(shadow.lastEvaluatedJavascript.contains("1.2"))
        compose.runOnIdle { document = ReaderSnapshot("较新正文", "second") }
        compose.waitForIdle(); assertEquals(initialUrl, shadow.lastLoadedUrl)
        compose.runOnIdle {
            shadow.webViewClient.shouldOverrideUrlLoading(view, request("first", "ready")); assertEquals(0f, view.alpha, 0f)
            shadow.webViewClient.shouldOverrideUrlLoading(view, request("first", "link")); assertTrue(links.isEmpty())
            shadow.webViewClient.shouldOverrideUrlLoading(view, request("second", "ready")); assertEquals(1f, view.alpha, 0f)
            shadow.webViewClient.shouldOverrideUrlLoading(view, request("second", "link")); assertEquals(1, links.size)
        }
    }
}
