package com.elio.jianyu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elio.jianyu.telemetry.TelemetryEvent
import com.elio.jianyu.ui.CardBg

internal fun diagnosticSize(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MiB", bytes.toDouble() / 1024 / 1024)

@Composable internal fun DiagnosticPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable internal fun DiagnosticFilterField(label: String, value: String, changed: (String) -> Unit) {
    OutlinedTextField(value, changed, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun DiagnosticEvent(
    event: TelemetryEvent, expanded: Boolean, ui: TelemetryUiState, onToggle: () -> Unit,
    onTab: (String) -> Unit, onPage: (Long) -> Unit, onCopy: (String) -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
    onFormatted: () -> Unit,
) {
    val presentation = telemetryEventPresentation(event)
    val status = when {
        event.recoveryResult == "WAITING" -> "等待自动重试"
        event.captureState == "RECORDING" -> "采集中"
        event.failureType == "CANCELLED" -> "已取消"
        event.failureType == "STREAM_STALLED" -> "流式响应无进展"
        event.failureType == "STREAM_FAILED" -> "流式生成失败"
        event.failureType == "TIMEOUT" -> "生成超时"
        else -> presentation.statusText
    }
    Column(Modifier.fillMaxWidth().testTag(SettingsTestTags.telemetryEvent(event.id))) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp)) {
            Text("[${event.keyName ?: event.keyId ?: "无 Key"}] ${event.model ?: event.phase ?: "未知模型"}", style = MaterialTheme.typography.titleSmall)
            Text(status, color = if (presentation.isSuccess || event.captureState == "RECORDING" || event.recoveryResult == "WAITING") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("${event.method.orEmpty()} ${event.endpoint}", style = MaterialTheme.typography.bodySmall)
            Text("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA).format(java.util.Date(event.timestamp))} · 尝试 ${event.retryCount + 1}", style = MaterialTheme.typography.bodySmall)
            if (event.captureState == "INCOMPLETE") Text("采集不完整：${event.captureError.orEmpty()}", color = MaterialTheme.colorScheme.error)
            if (event.historical) Text("历史预览：原版已脱敏或截断的内容无法还原")
        }
        if (expanded) {
            val tabs = listOf("概览", "请求", "响应", "流式事件", "异常与阶段")
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(ui.detailTab).coerceAtLeast(0), edgePadding = 0.dp) {
                tabs.forEach { tab -> Tab(selected = ui.detailTab == tab, onClick = { onTab(tab) }, text = { Text(tab) }) }
            }
            SelectionContainer {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    when (ui.detailTab) {
                        "概览" -> {
                            Text("请求耗时：${event.durationMs} ms")
                            event.statusCode?.let { Text("状态码：HTTP $it") }
                            Text("首个响应字节：${event.firstByteMs?.let { "$it ms" } ?: "未知"}")
                            Text("用途：${event.purpose ?: "未知"} · 协议：${event.protocol ?: "未知"}")
                            Text("会话：${event.sessionId ?: "未知"} · 问题：${event.questionId ?: "未知"} · 角色：${event.characterId ?: "未知"}")
                            Text("输入 Token：${event.inputTokens ?: "未知"} · 输出 Token：${event.outputTokens ?: "未知"}")
                            Text("阶段：${event.phase ?: "未知"} · 阶段结果：${event.recoveryResult ?: "未知"}")
                            event.phaseDetails?.let { Text(it) }
                            Text("操作编号：${event.operationId}\n请求编号：${event.id}")
                            Text(event.connection ?: "连接信息：未知")
                            event.failureType?.let { Text("错误分类：$it") }
                            event.errorMessage?.let { Text("异常详情：$it") }
                            if (event.requestFile == null && event.responseFile == null) Text("基础信息记录：未保存原始正文")
                        }
                        "请求", "响应", "流式事件" -> {
                            val header = when (ui.detailTab) { "请求" -> event.requestHeaders; "响应" -> event.responseHeaders; else -> null }
                            header?.let { Text("请求头 / 响应头"); DiagnosticRawText(it) }
                            if (ui.detailTab == "响应") Text("类型：${event.contentType ?: "未知"} · 压缩：${event.responseEncoding ?: "无"}")
                            if (ui.detailTab == "流式事件") Text("按接收顺序保存，receivedAt 为事件接收时间；data 保留服务端原始事件内容。")
                            else TextButton(onClick = onFormatted, enabled = !ui.loading) { Text(if (ui.formatted) "查看原始全文" else "格式化 JSON 全文") }
                            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            ui.detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (ui.page.isEmpty() && !ui.loading) Text("正文为空、未采集或文件尚未写入；二进制原始文件可通过 ZIP 导出。")
                            else if (ui.detailTab == "流式事件") {
                                ui.page.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                                    val envelope = runCatching { org.json.JSONObject(line) }.getOrNull()
                                    if (envelope != null && envelope.has("receivedAt")) {
                                        Text("接收时间：${envelope.optLong("receivedAt")}", style = MaterialTheme.typography.labelSmall)
                                        DiagnosticRawText(envelope.optString("data"))
                                    } else DiagnosticRawText(line)
                                }
                            } else DiagnosticRawText(ui.page)
                        }
                        else -> {
                            Text("${event.failureType ?: "无业务异常"}\n${event.errorMessage.orEmpty()}")
                            Text("阶段：${event.phase ?: "未知"}\n执行结果：${event.recoveryResult ?: "未知"}")
                            event.phaseDetails?.let { Text(it) }
                            Text("采集状态：${event.captureState}\n${event.captureError.orEmpty()}")
                            Text(event.connection.orEmpty())
                        }
                    }
                }
            }
            if (ui.detailTab in listOf("请求", "响应", "流式事件")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPage(0) }, enabled = ui.pageOffset > 0) { Text("回到开头") }
                OutlinedButton(onClick = { onPage(ui.nextPageOffset) }, enabled = ui.hasMore && !ui.loading) { Text("下一页") }
                Text("偏移 ${ui.pageOffset}", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("请求", "响应", "异常", "整条").forEach { kind -> TextButton(onClick = { onCopy(kind) }, enabled = ui.busyMessage == null) { Text("复制$kind") } }
                TextButton(onClick = onExport) { Text("导出单条") }
                TextButton(onClick = onDelete) { Text("删除单条") }
            }
        }
        HorizontalDivider()
    }
}

@Composable private fun DiagnosticRawText(text: String) {
    var pretty by remember(text) { mutableStateOf(false) }
    val formatted = remember(text) { runCatching {
        if (text.trimStart().startsWith("{")) org.json.JSONObject(text).toString(2)
        else if (text.trimStart().startsWith("[")) org.json.JSONArray(text).toString(2) else null
    }.getOrNull() }
    if (formatted != null) TextButton(onClick = { pretty = !pretty }) { Text(if (pretty) "查看原始文本" else "格式化 JSON") }
    Text(if (pretty) formatted ?: text else text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
