package com.elio.lifearchive.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.domain.sync.*

@Composable
fun ComputerSyncPanel(model: SettingsViewModel, beforeSync: suspend () -> Boolean = { true }) {
    val repository = model.sync ?: return
    val state by repository.state.collectAsStateWithLifecycle()
    var address by rememberSaveable(state.address) { mutableStateOf(state.address) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(repository) { repository.refreshTrash() }
    HorizontalDivider()
    Text("电脑同步", style = MaterialTheme.typography.titleMedium)
    Text("同一局域网内手动同步全部笔记与附件。请先保存手机草稿，并暂停电脑编辑。")
    OutlinedTextField(address, { address = it }, label = { Text("电脑同步地址") }, placeholder = { Text("http://192.168.1.8:3000/api/sync") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { code = it }, label = { Text("电脑显示的配对码") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { model.connectComputer(address, code); code = "" }, enabled = !state.busy && address.isNotBlank() && code.isNotBlank()) { Text(if (state.paired) "重新配对" else "配对电脑") }
        TextButton(onClick = model::testComputer, enabled = !state.busy && state.paired) { Text("测试连接") }
    }
    Button(onClick = { model.syncComputer(beforeSync = beforeSync) }, enabled = !state.busy && state.paired) { Text(if (state.busy) "同步中…" else "立即同步") }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(state.message)
    if (state.total > 0) Text("${state.completed} / ${state.total} 项 · 传输 ${state.transferredBytes / 1024} KiB")
    state.conflicts.forEach { conflict ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("需要选择版本", style = MaterialTheme.typography.titleSmall)
                Text("手机：${conflict.phone?.path ?: "已删除"}\n电脑：${conflict.computer?.path ?: "已删除"}")
                Row {
                    TextButton({ model.syncComputer(mapOf(conflict.key to SyncChoice.PHONE), beforeSync) }, enabled = !state.busy) { Text("保留手机版") }
                    TextButton({ model.syncComputer(mapOf(conflict.key to SyncChoice.COMPUTER), beforeSync) }, enabled = !state.busy) { Text("保留电脑版") }
                }
                TextButton({ model.syncComputer(mapOf(conflict.key to SyncChoice.SKIP), beforeSync) }, enabled = !state.busy) { Text("暂时跳过") }
            }
        }
    }
    state.issues.forEach { Text("${it.path}：${it.message}", color = MaterialTheme.colorScheme.error) }
    if (state.trash.isNotEmpty()) {
        Text("同步附件回收站", style = MaterialTheme.typography.titleSmall)
        state.trash.forEach { item ->
            TextButton({ model.restoreSyncAttachment(item.token) }, enabled = !state.busy) { Text("恢复 ${item.entry.path}") }
        }
    }
}
