package com.elio.jianyu.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.elio.jianyu.telemetry.TelemetryEvent
import com.elio.jianyu.telemetry.TelemetryLevel
import com.elio.jianyu.telemetry.DiagnosticFilter
import com.elio.jianyu.ui.SlateBg

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelemetryScreen(
    uiState: TelemetryUiState, onBack: () -> Unit,
    onSelectLevel: (TelemetryLevel) -> Unit,
    onClearTelemetry: () -> Unit,
    onToggleEvent: (TelemetryEvent) -> Unit,
    onFilter: (DiagnosticFilter) -> Unit = {}, onCapacity: (Long) -> Unit = {},
    onTab: (String) -> Unit = {}, onPage: (Long) -> Unit = {}, onCopy: (TelemetryEvent, String) -> Unit = { _, _ -> },
    onExport: (List<TelemetryEvent>) -> Unit = {}, onExportAll: () -> Unit = {}, onDelete: (List<TelemetryEvent>) -> Unit = {},
    onExportGroup: (String) -> Unit = {}, onDeleteGroup: (String) -> Unit = {},
    onFormatted: () -> Unit = {},
    onLoadMore: () -> Unit = {}, onExportFiltered: () -> Unit = {},
) {
    var filtersExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<TelemetryEvent>?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    val groups = remember(uiState.events) { uiState.events.groupBy(TelemetryEvent::operationId).values.toList() }
    Surface(modifier = Modifier.fillMaxSize().testTag(SettingsTestTags.TELEMETRY_ROOT), color = SlateBg) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(title = "API 调试与诊断", onBack = onBack, showLeadingSpacer = true)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    DiagnosticPanel {
                        Text("采集设置", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(TelemetryLevel.OFF to "关闭", TelemetryLevel.METADATA_ONLY to "基础信息", TelemetryLevel.CONTENT_DEBUG to "完整记录").forEach { (level, label) ->
                                FilterChip(selected = uiState.level == level, onClick = { onSelectLevel(level) }, label = { Text(label) })
                            }
                        }
                        Text(telemetryLevelDescription(uiState.level))
                        Text("占用 ${diagnosticSize(uiState.usedBytes)} / ${diagnosticSize(uiState.capacity)} · 共 ${uiState.totalEvents} 条")
                        Text("当前显示：${uiState.events.count { it.method != null }} 次 HTTP 尝试 · ${uiState.events.count { it.method != null && it.failureType != null }} 次失败")
                        uiState.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        uiState.busyMessage?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(100L shl 20, 500L shl 20, 1L shl 30).forEach { bytes ->
                                FilterChip(selected = uiState.capacity == bytes, onClick = { onCapacity(bytes) }, label = { Text(diagnosticSize(bytes)) })
                            }
                        }
                        Text("满后暂停采集；关闭采集保留历史。清理后自动恢复。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    DiagnosticPanel {
                        OutlinedTextField(value = uiState.filter.query, onValueChange = { onFilter(uiState.filter.copy(query = it)) }, label = { Text("搜索元数据和完整正文") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TextButton(onClick = { filtersExpanded = !filtersExpanded }) { Text(if (filtersExpanded) "收起筛选" else "展开筛选") }
                        if (filtersExpanded) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("全部", "Gemini", "DeepSeek").forEach { value -> FilterChip(uiState.filter.provider == value, { onFilter(uiState.filter.copy(provider = value)) }, { Text(value) }) } }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("全部", "成功", "失败").forEach { value -> FilterChip(uiState.filter.outcome == value, { onFilter(uiState.filter.copy(outcome = value)) }, { Text(value) }) } }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0 to "全部时间", 1 to "1 小时", 24 to "24 小时", 168 to "7 天").forEach { (hours, label) -> FilterChip(uiState.filter.hours == hours, { onFilter(uiState.filter.copy(hours = hours)) }, { Text(label) }) } }
                            DiagnosticFilterField("模型", uiState.filter.model) { onFilter(uiState.filter.copy(model = it)) }
                            DiagnosticFilterField("Key 名称或编号", uiState.filter.key) { onFilter(uiState.filter.copy(key = it)) }
                            DiagnosticFilterField("用途或阶段", uiState.filter.purpose) { onFilter(uiState.filter.copy(purpose = it)) }
                            DiagnosticFilterField("会话编号", uiState.filter.session) { onFilter(uiState.filter.copy(session = it)) }
                            TextButton(onClick = { onFilter(DiagnosticFilter()) }) { Text("重置筛选") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onExportFiltered, enabled = uiState.events.isNotEmpty() && uiState.busyMessage == null) { Text("导出筛选结果") }
                            OutlinedButton(onClick = onExportAll, enabled = uiState.totalEvents > 0 && uiState.busyMessage == null) { Text("导出全部") }
                            TextButton(onClick = { clearAll = true }, enabled = uiState.totalEvents > 0) { Text("清空全部") }
                        }
                    }
                }
                if (groups.isEmpty()) item { Text("暂无符合条件的诊断记录") }
                items(groups, key = { it.first().operationId }) { group ->
                    DiagnosticPanel {
                        val root = group.first()
                        Text("${root.purpose ?: root.phase ?: "独立请求"} · 当前页 ${group.size} 条记录", style = MaterialTheme.typography.titleSmall)
                        Text("会话 ${root.sessionId ?: "未知"} · 问题 ${root.questionId ?: "未知"} · 角色 ${root.characterId ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { onExportGroup(root.operationId) }) { Text("导出整组") }
                            TextButton(onClick = { pendingDelete = group; pendingGroup = root.operationId }) { Text("删除整组") }
                        }
                        group.sortedBy { it.timestamp }.forEach { event ->
                            DiagnosticEvent(event, event.id == uiState.expandedEventId, uiState,
                                onToggle = { onToggleEvent(event) }, onTab = onTab, onPage = onPage, onFormatted = onFormatted,
                                onCopy = { onCopy(event, it) }, onExport = { onExport(listOf(event)) }, onDelete = { pendingDelete = listOf(event) })
                        }
                    }
                }
                if (uiState.moreRecords) item { OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多记录") } }
            }
        }
    }
    if (clearAll || pendingDelete != null) AlertDialog(onDismissRequest = { clearAll = false; pendingDelete = null; pendingGroup = null },
        title = { Text(if (clearAll) "清空全部诊断记录？" else "删除选中的诊断记录？") },
        text = { Text("同时删除对应正文文件。会话内容和 API Key 不受影响。") },
        confirmButton = { TextButton(onClick = { if (clearAll) onClearTelemetry() else if (pendingGroup != null) onDeleteGroup(pendingGroup!!) else pendingDelete?.let(onDelete); clearAll = false; pendingDelete = null; pendingGroup = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { clearAll = false; pendingDelete = null; pendingGroup = null }) { Text("取消") } })
}
