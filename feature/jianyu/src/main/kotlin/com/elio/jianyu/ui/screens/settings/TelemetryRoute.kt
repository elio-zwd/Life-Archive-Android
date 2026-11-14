package com.elio.jianyu.ui.screens.settings

import com.elio.jianyu.ui.lifecycle.sessionCoroutineScope as rememberCoroutineScope

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.elio.jianyu.network.AiUseCase
import com.elio.jianyu.telemetry.*
import kotlinx.coroutines.*

@Composable
fun TelemetryRoute(
    diagnostics: com.elio.jianyu.telemetry.DiagnosticServices,
currentSessionId: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val events by diagnostics.events.collectAsStateWithLifecycle()
    val revision by diagnostics.revision.collectAsStateWithLifecycle()
    val payloadRevision by diagnostics.payloadRevision.collectAsStateWithLifecycle()
    val totalCount by diagnostics.totalCount.collectAsStateWithLifecycle()
    var recordLimit by remember { mutableIntStateOf(200) }
    var moreRecords by remember { mutableStateOf(false) }
    val level by diagnostics.level.collectAsStateWithLifecycle()
    val storageError by diagnostics.storageError.collectAsStateWithLifecycle()
    val usedBytes by diagnostics.usedBytes.collectAsStateWithLifecycle()
    val capacity by diagnostics.capacity.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(DiagnosticFilter()) }
    var filtered by remember { mutableStateOf(events) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("概览") }
    var offset by remember { mutableLongStateOf(0) }
    var page by remember { mutableStateOf("") }
    var nextOffset by remember { mutableLongStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var formatted by remember { mutableStateOf(false) }
    var exportSelection by remember { mutableStateOf<List<TelemetryEvent>?>(emptyList()) }
    var exportGroup by remember { mutableStateOf<String?>(null) }
    var exportFilter by remember { mutableStateOf<DiagnosticFilter?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = "正在导出…"
            try { diagnostics.export(uri.toString(), exportSelection, exportGroup, exportFilter); Toast.makeText(context, "诊断记录已导出", Toast.LENGTH_SHORT).show() }
            catch (e: Exception) { Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show() }
            finally { busy = null; exportSelection = emptyList() }
        }
    }
    LaunchedEffect(revision, filter, recordLimit) {
        val result = diagnostics.search(filter, recordLimit)
        filtered = result.first; moreRecords = result.second
    }
    val selected = filtered.firstOrNull { it.id == expanded }
    LaunchedEffect(selected, tab, offset, formatted, if (selected?.captureState == "RECORDING" && !formatted) payloadRevision else 0L) {
        page = ""; detailError = null; more = false
        if (selected == null) return@LaunchedEffect
        val file = when (tab) { "请求" -> selected.requestFile; "响应" -> selected.responseFile; "流式事件" -> selected.streamEventsFile; else -> null }
        if (file != null) {
            loading = true
            try {
                val binary = tab == "响应" && selected.contentType.orEmpty().let { it.startsWith("audio/") || it.startsWith("image/") || it.startsWith("video/") || it.startsWith("application/octet-stream") }
                if (binary) page = "二进制文件：$file\n文件大小：${diagnostics.payloadSize(file)} bytes\n通过 ZIP 导出完整原始文件。"
                else { val selectedFile = if (formatted) diagnostics.formattedFile(file) else file
                    val result = diagnostics.readPage(selectedFile, offset); page = result.first; more = result.second; nextOffset = result.third }
            }
            catch (e: Exception) { detailError = "读取失败：${e.message}" }
            finally { loading = false }
        }
    }
    val uiState = TelemetryUiState(events = filtered, level = level, storageError = storageError, expandedEventId = expanded,
        usedBytes = usedBytes, capacity = capacity, filter = filter, totalEvents = totalCount, detailTab = tab, page = page,
        pageOffset = offset, nextPageOffset = nextOffset, hasMore = more, loading = loading, detailError = detailError, busyMessage = busy, formatted = formatted, moreRecords = moreRecords)
    fun export(selection: List<TelemetryEvent>?, operation: String? = null, query: DiagnosticFilter? = null) {
        exportSelection = selection; exportGroup = operation; exportFilter = query
        exporter.launch("jianyu-api-${System.currentTimeMillis()}.zip")
    }
    TelemetryScreen(uiState = uiState, onBack = onBack,
        onSelectLevel = { diagnostics.setLevel(it) },
        onClearTelemetry = { diagnostics.clearAllTelemetry() },
        onToggleEvent = { expanded = if (expanded == it.id) null else it.id; offset = 0; tab = "概览"; formatted = false },
        onFilter = { filter = it; recordLimit = 200 }, onCapacity = { diagnostics.setCapacity(it) },
        onTab = { tab = it; offset = 0; formatted = false }, onPage = { offset = it }, onFormatted = { formatted = !formatted; offset = 0 },
        onCopy = { event, kind -> scope.launch {
            busy = "正在复制…"
            try {
                val text = when (kind) {
                    "请求" -> "${event.method} ${event.endpoint}\n${event.requestHeaders.orEmpty()}\n" + diagnostics.wholeText(event.requestFile)
                    "响应" -> "HTTP ${event.statusCode}\n${event.responseHeaders.orEmpty()}\n" + diagnostics.wholeText(event.responseFile)
                    "异常" -> event.errorMessage.orEmpty()
                    else -> diagnostics.eventJson(event) + "\n请求正文\n" + diagnostics.wholeText(event.requestFile) + "\n响应正文\n" + diagnostics.wholeText(event.responseFile)
                }
                check(text.length <= 200_000) { "内容超过剪贴板容量，请使用 ZIP 导出" }
                clipboard.setText(AnnotatedString(text)); Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, "复制失败，可改用 ZIP 导出：${e.message}", Toast.LENGTH_LONG).show() }
            finally { busy = null }
        } },
        onExport = { export(it) }, onExportAll = { export(null) }, onDelete = { diagnostics.delete(it.map(TelemetryEvent::id).toSet()) },
        onExportGroup = { id -> export(null, operation = id) }, onDeleteGroup = { diagnostics.deleteOperation(it) },
        onExportFiltered = { export(null, query = filter) }, onLoadMore = { recordLimit += 200 })
}
