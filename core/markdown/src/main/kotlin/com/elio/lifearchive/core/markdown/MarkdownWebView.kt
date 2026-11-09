package com.elio.lifearchive.core.markdown

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 受控本地 Markdown 宿主。文档、附件、缓存和链接能力均由调用方提供；
 * 此模块既不知道笔记 ID 的含义，也不能访问任何业务 Repository 或文件目录。
 */
@Composable
fun MarkdownWebView(
    documentId: String,
    revision: String,
    configuration: String,
    initialOffset: Int,
    onRequest: suspend (WebResourceRequest) -> WebResourceResponse?,
    onAction: suspend (Uri) -> String?,
    onCacheMessage: suspend (JSONObject) -> Boolean = { false },
    onOffset: (Int) -> Unit = {},
    onLoad: (MarkdownLoadEvent) -> Unit = {},
    navigation: MarkdownNavigation? = null,
) = key(documentId) {
    MarkdownDocumentView(documentId, revision, configuration, initialOffset, onRequest, onAction, onCacheMessage, onOffset, onLoad, navigation)
}

@Composable
private fun MarkdownDocumentView(
    documentId: String,
    revision: String,
    configuration: String,
    initialOffset: Int,
    onRequest: suspend (WebResourceRequest) -> WebResourceResponse?,
    onAction: suspend (Uri) -> String?,
    onCacheMessage: suspend (JSONObject) -> Boolean,
    onOffset: (Int) -> Unit,
    onLoad: (MarkdownLoadEvent) -> Unit,
    navigation: MarkdownNavigation?,
) {
    val scope = rememberCoroutineScope()
    val currentRevision by rememberUpdatedState(revision)
    val currentConfiguration by rememberUpdatedState(configuration)
    val currentRequest by rememberUpdatedState(onRequest)
    val currentAction by rememberUpdatedState(onAction)
    val currentCache by rememberUpdatedState(onCacheMessage)
    val currentOffset by rememberUpdatedState(onOffset)
    val currentLoad by rememberUpdatedState(onLoad)
    val initial = remember(documentId) { initialOffset }
    var readyRevision by remember(documentId) { mutableStateOf<String?>(null) }
    var revealed by remember(documentId) { mutableStateOf(false) }
    val background = MaterialTheme.colorScheme.background
    fun bindNavigation(view: WebView) {
        navigation?.request = {
            val captured = currentRevision
            view.evaluateJavascript("window.readerOutline?.() ?? []") { result ->
                if (view.tag != null && captured == currentRevision) {
                    navigation.headings = runCatching {
                        val values = JSONArray(result)
                        (0 until values.length()).mapNotNull { i ->
                            val item = values.optJSONObject(i) ?: return@mapNotNull null
                            val offset = item.optInt("offset", -1)
                            val level = item.optInt("level")
                            val title = item.optString("title").take(256)
                            if (offset < 0 || level !in 1..6 || title.isBlank()) null else MarkdownHeading(title, level, offset)
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }
        navigation?.jump = { offset -> if (offset >= 0 && view.tag != null) view.evaluateJavascript("window.readerJump?.($offset)", null) }
    }
    Box(Modifier.fillMaxSize().background(background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    alpha = 0f
                    setBackgroundColor(background.toArgb())
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    val loader = WebViewAssetLoader.Builder().addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(this, "ReaderCache", setOf(MarkdownProtocol.ORIGIN)) { view, message, origin, mainFrame, reply ->
                            val raw = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
                            if (view.tag == null || raw.length > MarkdownProtocol.MAX_MESSAGE_LENGTH) return@addWebMessageListener
                            val captured = currentRevision
                            scope.launch(Dispatchers.Default) {
                                val value = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
                                if (!MarkdownProtocol.acceptsMessage(mainFrame, origin.toString(), captured, value)) return@launch
                                if (!withContext(Dispatchers.Main) { view.tag != null && captured == currentRevision }) return@launch
                                when (value.optString("type")) {
                                    "phase" -> {
                                        val phase = runCatching { MarkdownLoadPhase.valueOf(value.getString("phase")) }.getOrNull() ?: return@launch
                                        withContext(Dispatchers.Main) {
                                            if (view.tag != null && captured == currentRevision) currentLoad(MarkdownLoadEvent(phase, captured, value.optLong("elapsed").coerceIn(0, 3600000), value.optBoolean("cacheHit"), value.optInt("artifactHits").coerceAtLeast(0)))
                                        }
                                    }
                                    "cache-put" -> {
                                        val accepted = try { currentCache(value) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                                        withContext(Dispatchers.Main) {
                                            if (view.tag != null && captured == currentRevision) reply.postMessage(JSONObject().put("request", value.optString("request")).put("accepted", accepted).toString())
                                        }
                                    }
                                }
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (view.tag != null) view.evaluateJavascript(configurationScript(currentConfiguration), null)
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            if (view.tag == null || !MarkdownProtocol.isLocal(request.url)) return missing()
                            return try {
                                runBlocking(Dispatchers.IO) { currentRequest(request) }
                                    ?: if (MarkdownProtocol.isStaticAsset(request.url)) loader.shouldInterceptRequest(request.url) ?: missing() else missing()
                            } catch (_: Exception) { missing() }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (view.tag == null) return true
                            val uri = request.url
                            val captured = currentRevision
                            if (MarkdownProtocol.acceptsAction(uri, captured)) {
                                when (uri.getQueryParameter("type")) {
                                    "ready" -> {
                                        readyRevision = captured
                                        revealed = true
                                        view.alpha = 1f
                                        currentLoad(MarkdownLoadEvent(MarkdownLoadPhase.FIRST_SCREEN, captured, uri.getQueryParameter("value")?.toLongOrNull() ?: 0, uri.getQueryParameter("cacheHit") == "true", uri.getQueryParameter("artifactHits")?.toIntOrNull() ?: 0))
                                    }
                                    "error" -> { revealed = true; view.alpha = 1f }
                                    else -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        val script = currentAction(uri)
                                        if (script != null && view.tag != null && captured == currentRevision) view.evaluateJavascript(script, null)
                                    }
                                }
                            } else if (!MarkdownProtocol.isLocal(uri) && uri.scheme in setOf("https", "http", "mailto", "tel")) {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { if (view.tag != null && captured == currentRevision) currentAction(uri) }
                            }
                            return true
                        }
                    }
                    var pending = false
                    setOnScrollChangeListener { _, _, _, _, _ ->
                        if (!pending && readyRevision == currentRevision && tag != null) {
                            val captured = currentRevision
                            pending = true
                            postDelayed({
                                pending = false
                                if (tag != null && captured == currentRevision && readyRevision == captured) evaluateJavascript("window.readerOffset?.() ?? 0") { result ->
                                    if (tag != null && captured == currentRevision) result.toIntOrNull()?.let(currentOffset)
                                }
                            }, 150)
                        }
                    }
                    tag = configuration
                    bindNavigation(this)
                    val config = JSONObject(configuration)
                    loadUrl(Uri.parse("${MarkdownProtocol.ORIGIN}/reader/index.html").buildUpon()
                        .appendQueryParameter("note", documentId).appendQueryParameter("offset", initial.toString())
                        .appendQueryParameter("scale", config.optDouble("scale", 1.0).toString())
                        .appendQueryParameter("dark", config.optBoolean("dark").toString())
                        .appendQueryParameter("theme", config.optString("theme"))
                        .appendQueryParameter("auto", config.optBoolean("auto").toString())
                        .appendQueryParameter("rev", revision).build().toString())
                }
            },
            update = { view ->
                bindNavigation(view)
                view.setBackgroundColor(background.toArgb())
                if (view.tag != configuration) { view.tag = configuration; view.evaluateJavascript(configurationScript(configuration), null) }
            },
            onRelease = { view ->
                view.tag = null
                navigation?.request = null
                navigation?.jump = null
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.destroy()
            },
        )
        if (!revealed) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun configurationScript(configuration: String) = "window.readerUpdate ? window.readerUpdate($configuration) : (window.readerPendingConfiguration = $configuration)"
private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("资源不可用".toByteArray()))
