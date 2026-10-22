package com.elio.lifearchive.feature.editor

import android.graphics.Color
import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

data class ReaderServices(val id: NoteId, val notes: NotesRepository, val library: LibraryRepository, val remote: RemoteCacheRepository, val automatic: Boolean, val message: (String) -> Unit)

object WebReaderRenderer : ReaderRenderer {
    @Composable override fun Content(document: ParsedDocument, fontScale: Float, initialOffset: Int, onOffset: (Int) -> Unit, loadImage: suspend (String) -> ByteArray?, onLink: (String) -> Unit, services: ReaderServices?) {
        if (services == null) { ComposeReaderRenderer.Content(document, fontScale, initialOffset, onOffset, loadImage, onLink); return }
        val scope = rememberCoroutineScope()
        val currentOffset by rememberUpdatedState(onOffset)
        val currentLink by rememberUpdatedState(onLink)
        val currentDocument by rememberUpdatedState(document)
        val background = androidx.compose.material3.MaterialTheme.colorScheme.background
        val darkTheme = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.red > background.red
        val revision = remember(document.source) { java.util.UUID.randomUUID().toString() }
        val url = "https://appassets.androidplatform.net/reader/index.html?note=${services.id.value}&offset=$initialOffset&scale=$fontScale&dark=${darkTheme}&auto=${services.automatic}&rev=$revision"
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                val loader = WebViewAssetLoader.Builder().addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url
                        if (uri.host != "appassets.androidplatform.net") return missing()
                        return try {
                            runBlocking(Dispatchers.IO) {
                                when (uri.path) {
                                    "/reader/document" -> {
                                        val id = NoteId(uri.getQueryParameter("note") ?: services.id.value)
                                        val snapshot = services.notes.read(id)
                                        if (snapshot !is NoteResult.Success) return@runBlocking missing()
                                        val source = if (id == services.id) currentDocument.source else snapshot.value.document.text
                                        val analysis = DocumentAnalyzer.analyze(source)
                                        json(JSONObject().put("id", id.value).put("path", snapshot.value.summary.path.value).put("html", HtmlProjection.render(source))
                                            .put("blocks", JSONArray(analysis.blocks.map { JSONObject().put("name", it.name).put("start", it.start).put("end", it.end) })))
                                    }
                                    "/reader/resolve" -> {
                                        val result = services.library.resolve(NoteId(uri.getQueryParameter("from") ?: services.id.value), uri.getQueryParameter("target").orEmpty())
                                        val value = (result as? NoteResult.Success)?.value ?: return@runBlocking missing()
                                        json(JSONObject().put("note", value.note?.value).put("attachment", value.attachment?.value).put("offset", value.location.offset ?: 0).put("missing", value.missing).put("ambiguous", JSONArray(value.ambiguous)))
                                    }
                                    "/reader/resource" -> {
                                        val path = uri.getQueryParameter("path") ?: return@runBlocking missing()
                                        val range = range(request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value)
                                        val result = services.library.openResource(LibraryPath(path), range?.first ?: 0, range?.second)
                                        val value = (result as? NoteResult.Success)?.value ?: return@runBlocking missing()
                                        response(value.info.mime, value.info.length, value.stream, range)
                                    }
                                    "/reader/cache-info" -> {
                                        val value = services.remote.find(uri.getQueryParameter("url").orEmpty(), uri.getQueryParameter("page") == "true")
                                        json(JSONObject().put("key", value?.key).put("fetched", value?.fetched).put("partial", value?.partial))
                                    }
                                    else -> {
                                        if (uri.path.orEmpty().startsWith("/reader/cached/")) {
                                            val parts = uri.pathSegments
                                            val range = range(request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value)
                                            val value = services.remote.open(parts.getOrNull(2).orEmpty(), parts.getOrNull(3) ?: "content", range?.first ?: 0, range?.second) ?: return@runBlocking missing()
                                            response(value.mime, value.length, value.stream, range)
                                        } else loader.shouldInterceptRequest(uri) ?: missing()
                                    }
                                }
                            }
                        } catch (_: Exception) { missing() }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (uri.host == "appassets.androidplatform.net" && uri.path == "/reader/action") {
                            when (uri.getQueryParameter("type")) {
                                "link" -> {
                                    val value = uri.getQueryParameter("value").orEmpty()
                                    currentLink(if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) value else Uri.Builder().scheme("archive-link").authority(uri.getQueryParameter("from") ?: services.id.value).appendQueryParameter("target", value).build().toString())
                                }
                                "cache", "refresh" -> scope.launch {
                                    val result = services.remote.fetch(uri.getQueryParameter("value").orEmpty(), uri.getQueryParameter("page") == "true", uri.getQueryParameter("type") == "refresh")
                                    services.message(if (result is NoteResult.Success) if (result.value.partial) "正文已缓存，部分图片未缓存" else "离线缓存完成" else "缓存失败：${(result as? NoteResult.StorageFailure)?.reason}")
                                    if (result is NoteResult.Success) view.evaluateJavascript("window.readerReload?.()", null)
                                }
                                "online" -> currentLink("online:" + uri.getQueryParameter("value").orEmpty())
                            }
                        } else if (uri.scheme in setOf("https", "http", "mailto", "tel")) currentLink(uri.toString())
                        return true
                    }
                }
                var pending = false
                setOnScrollChangeListener { _, _, _, _, _ ->
                    if (!pending) { pending = true; postDelayed({ pending = false; evaluateJavascript("window.readerOffset?.() ?? 0") { result -> result.toIntOrNull()?.let(currentOffset) } }, 150) }
                }
                tag = url
                loadUrl(url)
            }
        }, update = { view -> if (view.tag != url) { view.tag = url; view.loadUrl(url) } }, onRelease = { it.stopLoading(); it.webViewClient = WebViewClient(); it.destroy() })
    }
    private fun range(value: String?): Pair<Long, Long?>? {
        val match = value?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLong(); val end = match.groupValues[2].toLongOrNull()
        return start to end?.let { (it - start + 1).coerceAtLeast(0) }
    }
    private fun response(mime: String, total: Long, stream: java.io.InputStream, range: Pair<Long, Long?>?): WebResourceResponse {
        val start = range?.first ?: 0; val count = minOf(range?.second ?: (total - start), total - start)
        val headers = mutableMapOf("Content-Length" to count.toString(), "Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        if (range != null) headers["Content-Range"] = "bytes $start-${start + count - 1}/$total"
        return WebResourceResponse(mime, null, if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content", headers, stream)
    }
    private fun json(value: JSONObject) = WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream(value.toString().toByteArray()))
    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("资源不可用".toByteArray()))
}
