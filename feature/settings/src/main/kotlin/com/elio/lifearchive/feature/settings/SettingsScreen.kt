package com.elio.lifearchive.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.core.preferences.*
import com.elio.lifearchive.core.designsystem.LocalDocumentTheme
import com.elio.lifearchive.core.designsystem.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen(state: SettingsUiState, preferences: UserPreferences, onBack: () -> Unit, onTheme: () -> Unit, onFont: (Float) -> Unit, onRemote: (RemoteLoading) -> Unit, onClearCache: () -> Unit, onImportFiles: () -> Unit, onImportDirectory: () -> Unit, onExport: () -> Unit, onBackup: () -> Unit, onPickRestore: () -> Unit, onRestore: () -> Unit, onCancelRestore: () -> Unit, onImport: () -> Unit, onCancelImport: () -> Unit, onClearReaderCache: () -> Unit = {}, onImportTag: (String, String) -> Unit = { _, _ -> }, syncContent: @Composable () -> Unit = {}) {
    BackHandler(enabled = state.busy) { }
    Scaffold(topBar = { TopAppBar(title = { Text("设置与资料管理") }, navigationIcon = { TextButton(onClick = onBack, enabled = !state.busy) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text("显示", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onTheme, enabled = !state.busy) { Text("主题：${LocalDocumentTheme.current.label}") }
            Text("字号：${(preferences.fontScale * 100).toInt()}%")
            var scale by remember(preferences.fontScale) { mutableFloatStateOf(preferences.fontScale) }
            Slider(value = scale.coerceIn(.5f, 3f), onValueChange = { scale = it }, onValueChangeFinished = { onFont(scale) }, valueRange = .5f..3f, enabled = !state.busy)
            HorizontalDivider(); Text("阅读加速", style = MaterialTheme.typography.titleMedium)
            Text("已打开内容按需缓存；正文、草稿和附件不受清理影响，不进入完整备份。")
            Text("磁盘 ${(state.readerCacheBytes / 1024 / 1024)} MiB / 256 MiB · 内存 ${(state.readerMemoryBytes / 1024 / 1024)} MiB / 32 MiB")
            OutlinedButton(onClick = onClearReaderCache, enabled = !state.busy) { Text("清理阅读加速缓存") }
            HorizontalDivider(); Text("远程内容", style = MaterialTheme.typography.titleMedium)
            Text("本地笔记、公式、图表与库内附件始终离线可用。远程资源及网页正文副本属于可清理缓存。")
            Row { RemoteLoading.entries.forEach { mode -> TextButton(onClick = { onRemote(mode) }, enabled = !state.busy) { Text((if (mode == preferences.remoteLoading) "✓ " else "") + if (mode == RemoteLoading.MANUAL) "手动加载" else "自动缓存") } } }
            Text("缓存 ${(state.cacheBytes / 1024.0 / 1024).toInt()} MiB / 512 MiB；不进入完整备份。")
            OutlinedButton(onClick = onClearCache, enabled = !state.busy) { Text("清理远程缓存与网页副本") }
            syncContent()
            HorizontalDivider(); Text("导入与导出", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onImportFiles, enabled = !state.busy) { Text("导入 Markdown 文件") }
            OutlinedButton(onClick = onImportDirectory, enabled = !state.busy) { Text("导入目录（含附件）") }
            OutlinedButton(onClick = onExport, enabled = !state.busy) { Text("导出全部笔记与附件 ZIP") }
            Text("便携导出用于普通文件交换；完整备份另外保留应用状态、草稿和回收站。")
            HorizontalDivider(); Text("完整备份与恢复", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onBackup, enabled = !state.busy) { Text("导出完整备份") }
            OutlinedButton(onClick = onPickRestore, enabled = !state.busy) { Text("选择备份并验证") }
            Spacer(Modifier.height(30.dp))
        }
    }
    state.restore?.let { AlertDialog(onDismissRequest = { if (!state.busy) onCancelRestore() }, title = { Text("替换现有资料？") }, text = { Text("已验证备份。继续将替换笔记、附件、草稿、回收站和设置；当前编辑会话会结束。") }, confirmButton = { TextButton(onClick = onRestore, enabled = !state.busy) { Text("确认替换并恢复") } }, dismissButton = { TextButton(onClick = onCancelRestore, enabled = !state.busy) { Text("取消") } }) }
    state.importing?.let { prepared -> AlertDialog(onDismissRequest = { if (!state.busy) onCancelImport() }, title = { Text("确认导入与新标签") }, text = {
        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${prepared.notes} 篇笔记、${prepared.attachments} 个附件\n目标：${prepared.folder}\n未解析引用 ${prepared.unresolved.size} 个：${prepared.unresolved.take(8).joinToString("、")}\n原有文件不覆盖。原始写作时间未知时不补写。") }
            if (prepared.foreignTags.isNotEmpty()) item { Text("确认外来新标签：保留为采用，可映射为已有标签，留空为不采用。最多三级。") }
            items(prepared.foreignTags, key = { it }) { tag -> OutlinedTextField(prepared.tagDecisions[tag].orEmpty(), { onImportTag(tag, it) }, label = { Text(tag) }, enabled = !state.busy) }
        }
    }, confirmButton = { TextButton(onClick = onImport, enabled = !state.busy) { Text("确认标签并导入") } }, dismissButton = { TextButton(onClick = onCancelImport, enabled = !state.busy) { Text("取消") } }) }
}
