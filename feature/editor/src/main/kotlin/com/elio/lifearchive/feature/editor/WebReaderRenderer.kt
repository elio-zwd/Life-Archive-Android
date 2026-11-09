package com.elio.lifearchive.feature.editor

import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.core.markdown.MarkdownWebView
import com.elio.lifearchive.core.markdown.MarkdownProtocol
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.core.designsystem.LocalDocumentTheme
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject

data class ReaderServices(val id: NoteId, val notes: NotesRepository, val library: LibraryRepository, val remote: RemoteCacheRepository, val automatic: Boolean, val message: (String) -> Unit, val cache: ReaderCacheRepository? = null, val navigation: ReaderNavigation? = null, val onLoad: (ReaderLoadEvent) -> Unit = {})

/** 笔记资源与元信息适配层；共用 WebView 不持有笔记服务。 */
object WebReaderRenderer : ReaderRenderer {
    @Composable override fun Content(document: ReaderSnapshot, fontScale: Float, initialOffset: Int, onOffset: (Int) -> Unit, loadImage: suspend (String) -> ByteArray?, onLink: (String) -> Unit, services: ReaderServices?) {
        if (services == null) { ComposeReaderRenderer.Content(document, fontScale, initialOffset, onOffset, loadImage, onLink); return }
        val scope = rememberCoroutineScope()
        val currentLink by rememberUpdatedState(onLink)
        val currentDocument by rememberUpdatedState(document)
        val documentLoader = remember(services.id, services.notes, services.cache) { ReaderDocumentLoader(services.notes, services.cache, scope) }
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
                .put("cover", when (val cover = detail.cover) {
                    is NoteCover.Preset -> JSONObject().put("preset", cover.preset.key)
                    is NoteCover.Attachment -> JSONObject().put("attachment", cover.path.value)
                })
        }
        val configuration = JSONObject().put("revision", document.revision).put("scale", fontScale).put("dark", darkTheme).put("theme", pageTheme).put("auto", services.automatic).put("metadata", metadataJson ?: JSONObject.NULL).toString()
        MarkdownWebView(
            documentId = services.id.value,
            revision = document.revision,
            configuration = configuration,
            initialOffset = initialOffset,
            onOffset = onOffset,
            onLoad = services.onLoad,
            navigation = services.navigation,
            onCacheMessage = { value -> services.cache?.let { ReaderCacheMessages.put(value, it) } ?: false },
            onRequest = request@{ request ->
                val uri = request.url
                when (uri.path) {
                    "/reader/document" -> {
                        val id = NoteId(uri.getQueryParameter("note") ?: services.id.value)
                        val captured = currentDocument
                        if (id == services.id && uri.getQueryParameter("revision")?.let { it != captured.revision } == true) return@request missing()
                        documentLoader.load(id, services.id, captured)?.let(::json) ?: missing()
                    }
                    "/reader/render-cache" -> {
                        val cache = services.cache ?: return@request json(JSONObject())
                        val token = ReaderCacheToken(uri.getQueryParameter("namespace").orEmpty())
                        val kind = runCatching { ReaderArtifactKind.valueOf(uri.getQueryParameter("kind").orEmpty()) }.getOrNull()
                        if (kind == null || kind == ReaderArtifactKind.HTML) return@request json(JSONObject())
                        val value = cache.get(token, ReaderArtifactKey(kind, uri.getQueryParameter("key").orEmpty()))
                        json(JSONObject().put("value", value))
                    }
                    "/reader/resource-info" -> {
                        val path = LibraryPath(uri.getQueryParameter("path") ?: return@request missing())
                        val result = services.library.openResource(path, 0, null) as? NoteResult.Success ?: return@request missing()
                        result.value.stream.use { input ->
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                            json(JSONObject().put("width", bounds.outWidth.coerceAtLeast(0)).put("height", bounds.outHeight.coerceAtLeast(0)))
                        }
                    }
                    "/reader/resolve" -> {
                        val syntax = runCatching { LinkSyntax.valueOf(uri.getQueryParameter("syntax") ?: "WIKI") }.getOrDefault(LinkSyntax.WIKI)
                        val result = services.library.resolve(NoteId(uri.getQueryParameter("from") ?: services.id.value), uri.getQueryParameter("target").orEmpty(), syntax)
                        val value = (result as? NoteResult.Success)?.value ?: return@request missing()
                        json(JSONObject().put("note", value.note?.value).put("attachment", value.attachment?.value).put("offset", value.location.offset ?: 0).put("missing", value.missing).put("ambiguous", JSONArray(value.ambiguous)))
                    }
                    "/reader/resource" -> {
                        val path = uri.getQueryParameter("path") ?: return@request missing()
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
                        } else null
                    }
                }
            },
            onAction = { uri ->
                if (!MarkdownProtocol.isLocal(uri) || uri.path != "/reader/action") {
                    currentLink(uri.toString())
                    null
                } else when (uri.getQueryParameter("type")) {
                    "link" -> {
                        val value = uri.getQueryParameter("value").orEmpty()
                        currentLink(if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) value else Uri.Builder().scheme("archive-link").authority(uri.getQueryParameter("from") ?: services.id.value).appendQueryParameter("target", value).appendQueryParameter("syntax", uri.getQueryParameter("syntax") ?: "WIKI").build().toString())
                        null
                    }
                    "cache", "refresh" -> {
                        val captured = currentDocument.revision
                        val result = services.remote.fetch(uri.getQueryParameter("value").orEmpty(), uri.getQueryParameter("page") == "true", uri.getQueryParameter("type") == "refresh")
                        currentCoroutineContext().ensureActive()
                        if (captured == currentDocument.revision) {
                            services.message(if (result is NoteResult.Success) if (result.value.partial) "正文已缓存，部分图片未缓存" else "离线缓存完成" else "缓存失败：${(result as? NoteResult.StorageFailure)?.reason}")
                            if (result is NoteResult.Success) "window.readerResourceUpdated?.(${JSONObject.quote(uri.getQueryParameter("value").orEmpty())},${uri.getQueryParameter("page") == "true"})" else null
                        } else null
                    }
                    "online" -> { currentLink("online:" + uri.getQueryParameter("value").orEmpty()); null }
                    else -> null
                }
            },
        )
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
