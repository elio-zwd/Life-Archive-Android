package com.elio.lifearchive.feature.editor

import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.core.designsystem.LocalDocumentTheme
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject

data class ReaderServices(val id: NoteId, val notes: NotesRepository, val library: LibraryRepository, val remote: RemoteCacheRepository, val automatic: Boolean, val message: (String) -> Unit, val cache: ReaderCacheRepository? = null, val navigation: ReaderNavigation? = null, val onLoad: (ReaderLoadEvent) -> Unit = {})

object WebReaderRenderer : ReaderRenderer {
    @Composable override fun Content(document: ReaderSnapshot, fontScale: Float, initialOffset: Int, onOffset: (Int) -> Unit, loadImage: suspend (String) -> ByteArray?, onLink: (String) -> Unit, services: ReaderServices?) {
        if (services == null) { ComposeReaderRenderer.Content(document, fontScale, initialOffset, onOffset, loadImage, onLink); return }
        val scope = rememberCoroutineScope()
        val currentOffset by rememberUpdatedState(onOffset)
        val currentLink by rememberUpdatedState(onLink)
        val currentDocument by rememberUpdatedState(document)
        val currentLoad by rememberUpdatedState(services.onLoad)
        val documentLoader = remember(services.id, services.notes, services.cache) { ReaderDocumentLoader(services.notes, services.cache, scope) }
        val background = androidx.compose.material3.MaterialTheme.colorScheme.background
        val pageTheme = LocalDocumentTheme.current.name.lowercase(java.util.Locale.ROOT)
        val darkTheme = pageTheme == "graphite"
        // 元信息独立于首屏加载；索引不可用或读取失败时正文仍可阅读。
        val metadata by produceState<LibraryNote?>(null, services.id, services.notes, services.library) {
            try {
                // 保存可能在切回阅读之后完成；随后按通知刷新单篇元信息，不阻塞首屏。
                services.notes.observeNotes().collectLatest {
                    value = try { withContext(Dispatchers.IO) { (services.library.metadata(services.id) as? NoteResult.Success)?.value } }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { value = null }
        }
        val metadataJson = metadata?.let { detail ->
            JSONObject().put("folder", detail.summary.path.value.substringBeforeLast('/', ""))
                .put("modified", detail.modified).put("tags", JSONArray(detail.propertyTags))
        }
        val initial = remember(services.id) { initialOffset }
        var readyRevision by remember(services.id) { mutableStateOf<String?>(null) }
        var revealed by remember(services.id) { mutableStateOf(false) }
        val configuration = JSONObject().put("revision", document.revision).put("scale", fontScale).put("dark", darkTheme).put("theme", pageTheme).put("auto", services.automatic).put("metadata", metadataJson ?: JSONObject.NULL).toString()
        val currentConfiguration by rememberUpdatedState(configuration)
        val url = "https://appassets.androidplatform.net/reader/index.html?note=${services.id.value}&offset=$initial&scale=$fontScale&dark=${darkTheme}&theme=$pageTheme&auto=${services.automatic}&rev=${document.revision}"
        val navigation = services.navigation
        fun bindNavigation(view: WebView) {
            navigation?.request = {
                val captured = currentDocument.revision
                view.evaluateJavascript("window.readerOutline?.() ?? []") { result ->
                    if (view.tag != null && captured == currentDocument.revision) {
                        navigation.headings = runCatching {
                            val array = JSONArray(result)
                            (0 until array.length()).mapNotNull { i ->
                                val item = array.optJSONObject(i) ?: return@mapNotNull null
                                val offset = item.optInt("offset", -1); val level = item.optInt("level")
                                val title = item.optString("title").take(256)
                                if (offset < 0 || level !in 1..6 || title.isBlank()) null else ReaderHeading(title, level, offset)
                            }
                        }.getOrDefault(emptyList())
                    }
                }
            }
            navigation?.jump = { offset -> if (offset >= 0 && view.tag != null) view.evaluateJavascript("window.readerJump?.($offset)", null) }
        }
        Box(Modifier.fillMaxSize().background(background)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                alpha = 0f
                setBackgroundColor(background.toArgb())
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                val loader = WebViewAssetLoader.Builder().addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(this, "ReaderCache", setOf("https://appassets.androidplatform.net")) { view, message, origin, mainFrame, reply ->
                    val raw = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
                    if (view.tag == null || raw.length > 8 * 1024 * 1024 + 2048) return@addWebMessageListener
                    val captured = currentDocument.revision
                    scope.launch(Dispatchers.Default) {
                        val value = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
                        if (!ReaderCacheMessages.allowed(mainFrame, origin.toString(), captured, value)) return@launch
                        val valid = withContext(Dispatchers.Main) { view.tag != null && captured == currentDocument.revision }
                        if (!valid) return@launch
                        if (value.optString("type") == "phase") {
                            val phase = runCatching { ReaderLoadPhase.valueOf(value.getString("phase")) }.getOrNull() ?: return@launch
                            withContext(Dispatchers.Main) { if (view.tag != null && captured == currentDocument.revision) currentLoad(ReaderLoadEvent(phase, captured, value.optLong("elapsed").coerceIn(0, 3600000), value.optBoolean("cacheHit"), value.optInt("artifactHits").coerceAtLeast(0))) }
                        } else if (value.optString("type") == "cache-put" && services.cache != null) {
                            val accepted = try { ReaderCacheMessages.put(value, services.cache) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                            withContext(Dispatchers.Main) { if (view.tag != null && captured == currentDocument.revision && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) reply.postMessage(JSONObject().put("request", value.optString("request")).put("accepted", accepted).toString()) }
                        }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { if (view.tag != null) view.evaluateJavascript("window.readerUpdate?.($currentConfiguration)", null) }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url
                        if (view.tag == null || uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return missing()
                        return try {
                            runBlocking(Dispatchers.IO) {
                                when (uri.path) {
                                    "/reader/document" -> {
                                        val id = NoteId(uri.getQueryParameter("note") ?: services.id.value)
                                        val captured = currentDocument
                                        if (id == services.id && uri.getQueryParameter("revision")?.let { it != captured.revision } == true) return@runBlocking missing()
                                        documentLoader.load(id, services.id, captured)?.let(::json) ?: missing()
                                    }
                                    "/reader/render-cache" -> {
                                        val cache = services.cache ?: return@runBlocking json(JSONObject())
                                        val token = ReaderCacheToken(uri.getQueryParameter("namespace").orEmpty())
                                        val kind = runCatching { ReaderArtifactKind.valueOf(uri.getQueryParameter("kind").orEmpty()) }.getOrNull()
                                        if (kind == null || kind == ReaderArtifactKind.HTML) return@runBlocking json(JSONObject())
                                        val value = cache.get(token, ReaderArtifactKey(kind, uri.getQueryParameter("key").orEmpty()))
                                        json(JSONObject().put("value", value))
                                    }
                                    "/reader/resource-info" -> {
                                        val path = LibraryPath(uri.getQueryParameter("path") ?: return@runBlocking missing())
                                        val result = services.library.openResource(path, 0, null) as? NoteResult.Success ?: return@runBlocking missing()
                                        result.value.stream.use { input ->
                                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                                            json(JSONObject().put("width", bounds.outWidth.coerceAtLeast(0)).put("height", bounds.outHeight.coerceAtLeast(0)))
                                        }
                                    }
                                    "/reader/resolve" -> {
                                        val syntax = runCatching { LinkSyntax.valueOf(uri.getQueryParameter("syntax") ?: "WIKI") }.getOrDefault(LinkSyntax.WIKI)
                                        val result = services.library.resolve(NoteId(uri.getQueryParameter("from") ?: services.id.value), uri.getQueryParameter("target").orEmpty(), syntax)
                                        val value = (result as? NoteResult.Success)?.value ?: return@runBlocking missing()
                                        json(JSONObject().put("note", value.note?.value).put("attachment", value.attachment?.value).put("offset", value.location.offset ?: 0).put("missing", value.missing).put("ambiguous", JSONArray(value.ambiguous)))
                                    }
                                    "/reader/resource" -> {
                                        val path = uri.getQueryParameter("path") ?: return@runBlocking missing()
                                        resourceResponse(request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value) { offset, length ->
                                            val result = services.library.openResource(LibraryPath(path), offset, length)
                                            (result as? NoteResult.Success)?.value?.let { CacheStream(it.info.mime, it.info.length, it.stream) }
                                        }
                                    }
                                    "/reader/cache-info" -> {
                                        val value = services.remote.find(uri.getQueryParameter("url").orEmpty(), uri.getQueryParameter("page") == "true")
                                        json(JSONObject().put("key", value?.key).put("fetched", value?.fetched).put("partial", value?.partial))
                                    }
                                    else -> {
                                        if (uri.path.orEmpty().startsWith("/reader/cached/")) {
                                            val parts = uri.pathSegments
                                            resourceResponse(request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value) { offset, length -> services.remote.open(parts.getOrNull(2).orEmpty(), parts.getOrNull(3) ?: "content", offset, length) }
                                        } else loader.shouldInterceptRequest(uri) ?: missing()
                                    }
                                }
                            }
                        } catch (_: Exception) { missing() }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (view.tag == null) return true
                        val uri = request.url
                        if (uri.host == "appassets.androidplatform.net" && uri.path == "/reader/action") {
                            if (uri.getQueryParameter("revision") != currentDocument.revision) return true
                            when (uri.getQueryParameter("type")) {
                                "ready" -> if (uri.getQueryParameter("revision") == currentDocument.revision) {
                                    readyRevision = currentDocument.revision; revealed = true; view.alpha = 1f
                                    currentLoad(ReaderLoadEvent(ReaderLoadPhase.FIRST_SCREEN, currentDocument.revision, uri.getQueryParameter("value")?.toLongOrNull() ?: 0, uri.getQueryParameter("cacheHit") == "true", uri.getQueryParameter("artifactHits")?.toIntOrNull() ?: 0))
                                }
                                "error" -> if (uri.getQueryParameter("revision") == currentDocument.revision) { revealed = true; view.alpha = 1f }
                                "link" -> {
                                    val value = uri.getQueryParameter("value").orEmpty()
                                    currentLink(if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) value else Uri.Builder().scheme("archive-link").authority(uri.getQueryParameter("from") ?: services.id.value).appendQueryParameter("target", value).appendQueryParameter("syntax", uri.getQueryParameter("syntax") ?: "WIKI").build().toString())
                                }
                                "cache", "refresh" -> scope.launch {
                                    val captured = currentDocument.revision
                                    val result = services.remote.fetch(uri.getQueryParameter("value").orEmpty(), uri.getQueryParameter("page") == "true", uri.getQueryParameter("type") == "refresh")
                                    if (view.tag == null || captured != currentDocument.revision) return@launch
                                    services.message(if (result is NoteResult.Success) if (result.value.partial) "正文已缓存，部分图片未缓存" else "离线缓存完成" else "缓存失败：${(result as? NoteResult.StorageFailure)?.reason}")
                                    if (result is NoteResult.Success) view.evaluateJavascript("window.readerResourceUpdated?.(${JSONObject.quote(uri.getQueryParameter("value").orEmpty())},${uri.getQueryParameter("page") == "true"})", null)
                                }
                                "online" -> currentLink("online:" + uri.getQueryParameter("value").orEmpty())
                            }
                        } else if (uri.scheme in setOf("https", "http", "mailto", "tel")) currentLink(uri.toString())
                        return true
                    }
                }
                var pending = false
                setOnScrollChangeListener { _, _, _, _, _ ->
                    if (!pending && readyRevision == currentDocument.revision && tag != null) {
                        val captured = currentDocument.revision; pending = true
                        postDelayed({ pending = false; if (tag != null && captured == currentDocument.revision && readyRevision == captured) evaluateJavascript("window.readerOffset?.() ?? 0") { result -> if (tag != null && captured == currentDocument.revision) result.toIntOrNull()?.let(currentOffset) } }, 150)
                    }
                }
                tag = configuration
                bindNavigation(this)
                loadUrl(url)
            }
        }, update = { view ->
            bindNavigation(view)
            view.setBackgroundColor(background.toArgb())
            if (view.tag != configuration) { view.tag = configuration; view.evaluateJavascript("window.readerUpdate?.($configuration)", null) }
        }, onRelease = { it.tag = null; navigation?.request = null; navigation?.jump = null; it.stopLoading(); it.webViewClient = WebViewClient(); it.destroy() })
        if (!revealed) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    internal suspend fun resourceResponse(header: String?, open: suspend (Long, Long?) -> CacheStream?): WebResourceResponse {
        val info = open(0, 0) ?: return missing(); info.stream.close()
        val range = requestedRange(header, info.length)
        if (header != null && range == null) return WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${info.length}"), ByteArrayInputStream(byteArrayOf()))
        val start = range?.first ?: 0; val count = range?.let { it.last - it.first + 1 } ?: info.length
        val value = open(start, count) ?: return missing()
        val headers = mutableMapOf("Content-Length" to count.toString(), "Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        if (range != null) headers["Content-Range"] = "bytes $start-${range.last}/${info.length}"
        return WebResourceResponse(value.mime, null, if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content", headers, value.stream)
    }
    internal fun requestedRange(value: String?, total: Long): LongRange? {
        if (value == null || total <= 0) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(value) ?: return null
        val a = match.groupValues[1]; val b = match.groupValues[2]
        if (a.isEmpty()) { val count = b.toLongOrNull()?.takeIf { it > 0 } ?: return null; return (total - count).coerceAtLeast(0)..(total - 1) }
        val start = a.toLongOrNull()?.takeIf { it in 0 until total } ?: return null
        val end = if (b.isEmpty()) total - 1 else b.toLongOrNull()?.coerceAtMost(total - 1) ?: return null
        return if (end >= start) start..end else null
    }
    private fun json(value: JSONObject) = WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream(value.toString().toByteArray()))
    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("资源不可用".toByteArray()))
}
