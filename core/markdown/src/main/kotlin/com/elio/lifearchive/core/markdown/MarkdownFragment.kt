package com.elio.lifearchive.core.markdown

import android.net.Uri
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.elio.lifearchive.core.document.HtmlProjection
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 只展示调用方的正文；不持有笔记库、凭据、网络或文件访问能力。放入 Lazy 列表时离屏即销毁。 */
@OptIn(FlowPreview::class)
@Composable
fun MarkdownFragment(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 15.sp,
    dark: Boolean = false,
    maxHeight: Dp = 12000.dp,
    scrollable: Boolean = true,
    onContentHeight: (Dp) -> Unit = {},
    onLink: (String) -> Unit = {},
    onResource: (WebResourceRequest) -> WebResourceResponse? = { null },
) {
    val identity = remember { UUID.randomUUID().toString() }
    val clipboard = LocalClipboardManager.current
    val fontScale = LocalDensity.current.fontScale
    val text by rememberUpdatedState(markdown)
    val link by rememberUpdatedState(onLink)
    val resource by rememberUpdatedState(onResource)
    val heightChanged by rememberUpdatedState(onContentHeight)
    val canScroll by rememberUpdatedState(scrollable)
    val heightLimit by rememberUpdatedState(maxHeight)
    val response = remember { AtomicReference(JSONObject().put("html", "").put("blocks", org.json.JSONArray()).toString()) }
    var revision by remember { mutableStateOf("$identity-0") }
    var measured by remember { mutableStateOf(24.dp) }
    var currentView by remember { mutableStateOf<WebView?>(null) }
    // 固定节拍合并 token；不会因持续流入而永远等待 debounce，最后一帧也会提交。
    LaunchedEffect(identity) {
        var sequence = 0
        snapshotFlow { text }.sample(100).collectLatest { source ->
            val html = withContext(Dispatchers.Default) { HtmlProjection.render(source) }
            response.set(JSONObject().put("html", html).put("blocks", org.json.JSONArray()).toString())
            revision = "$identity-${++sequence}"
        }
    }
    val activeRevision by rememberUpdatedState(revision)
    val configuration = JSONObject().put("revision", revision).put("dark", dark).put("scale", fontSize.value * fontScale / 17f).toString()
    val latestConfiguration by rememberUpdatedState(configuration)
    val latestColor by rememberUpdatedState(color)
    val sent = remember { AtomicReference<String?>(null) }
    fun configure(view: WebView, force: Boolean = false) {
        val foreground = if (latestColor != Color.Unspecified) "document.documentElement.style.setProperty('--fg', '${String.format("#%06x", latestColor.toArgb() and 0xffffff)}');" else ""
        val signature = foreground + latestConfiguration
        if (!force && sent.get() == signature) return
        sent.set(signature)
        view.evaluateJavascript("$foreground window.readerUpdate ? window.readerUpdate($latestConfiguration) : (window.readerPendingConfiguration = $latestConfiguration)", null)
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().heightIn(min = 1.dp, max = maxHeight).height(measured.coerceAtLeast(1.dp).coerceAtMost(maxHeight)),
        factory = { context ->
            WebView(context).apply {
                currentView = this
                tag = identity
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                isVerticalScrollBarEnabled = false
                // 卡片纵向滑动交给外层列表；宽表格、代码仍可横向滚动和选择复制。
                var downX = 0f; var downY = 0f
                setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                        view.parent?.requestDisallowInterceptTouchEvent(kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY) || (canScroll && measured > heightLimit))
                    }
                    false
                }
                setOnScrollChangeListener { _, _, y, _, _ -> if (!canScroll && y != 0) scrollTo(scrollX, 0) }
                val assets = WebViewAssetLoader.Builder().addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(this, "ReaderFragment", setOf(MarkdownProtocol.ORIGIN)) { view, message, origin, main, _ ->
                        val raw = message.data ?: return@addWebMessageListener
                        if (raw.length > MarkdownProtocol.MAX_MESSAGE_LENGTH || view.tag != identity || !main || origin.toString() != MarkdownProtocol.ORIGIN) return@addWebMessageListener
                        val value = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
                        if (value.optString("revision") != activeRevision) return@addWebMessageListener
                        if (value.has("copy")) { clipboard.setText(AnnotatedString(value.getString("copy"))); return@addWebMessageListener }
                        val pixels = value.optDouble("height", -1.0)
                        if (!pixels.isFinite() || pixels < 0 || pixels > 10000000) return@addWebMessageListener
                        // CSS px 对应 Android dp；系统字体缩放已包含在调用方字号中。
                        val next = pixels.toFloat().dp.coerceAtLeast(1.dp)
                        if (next != measured) { measured = next; heightChanged(next) }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { if (view.tag == identity) configure(view, force = true) }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        if (view.tag != identity || !MarkdownProtocol.isLocal(request.url)) return fragmentResponse("资源不可用", "text/plain", 404)
                        return when (request.url.path) {
                            "/reader/fragment" -> fragmentResponse(response.get())
                            "/reader/resolve" -> resource(request) ?: fragmentResponse("{\"missing\":true}")
                            "/reader/cache-info" -> fragmentResponse("{}")
                            else -> resource(request) ?: if (MarkdownProtocol.isStaticAsset(request.url)) assets.shouldInterceptRequest(request.url) ?: fragmentResponse("资源不可用", "text/plain", 404) else fragmentResponse("资源不可用", "text/plain", 404)
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (view.tag != identity) return true
                        if (MarkdownProtocol.isLocal(uri)) {
                            if (MarkdownProtocol.acceptsAction(uri, activeRevision) && uri.getQueryParameter("type") == "link") link(uri.getQueryParameter("value").orEmpty())
                        } else if (uri.scheme in setOf("https", "http", "mailto", "tel")) link(uri.toString())
                        return true
                    }
                }
                loadUrl(Uri.parse("${MarkdownProtocol.ORIGIN}/reader/index.html").buildUpon().appendQueryParameter("fragment", "true")
                    .appendQueryParameter("note", identity).appendQueryParameter("rev", revision).appendQueryParameter("scale", (fontSize.value * fontScale / 17f).toString())
                    .appendQueryParameter("dark", dark.toString()).build().toString())
            }
        },
        update = { if (!canScroll && it.scrollY != 0) it.scrollTo(it.scrollX, 0); configure(it) },
        onRelease = { view ->
            view.tag = null; view.stopLoading(); view.webViewClient = WebViewClient(); view.destroy()
            if (currentView === view) currentView = null
        },
    )
}

private fun fragmentResponse(value: String, mime: String = "application/json", status: Int = 200) =
    WebResourceResponse(mime, "UTF-8", status, if (status == 200) "OK" else "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))
