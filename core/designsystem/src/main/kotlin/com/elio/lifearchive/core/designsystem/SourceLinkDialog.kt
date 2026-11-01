package com.elio.lifearchive.core.designsystem

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.TextRange

data class SourceLinkDialogState(
    val current: String = "",
    val text: String = "",
    val links: List<String> = emptyList(),
    val selected: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
)

/** 调用方仅在明确点击更新／重新粘贴时调用，不强制转换剪贴板 URI。 */
fun readPlainClipboardText(context: Context): String? {
    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
    return if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
}

@Composable
fun SourceLinkDialog(
    state: SourceLinkDialogState, preview: Boolean,
    onText: (String) -> Unit, onSelect: (String) -> Unit, onPaste: () -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    // 输入状态独立保留光标；仅在重新粘贴或实际输入改变时同步外部候选。
    val input = rememberTextFieldState(initialText = state.text)
    val externalText by rememberUpdatedState(state.text)
    val updateText by rememberUpdatedState(onText)
    LaunchedEffect(state.text) {
        if (input.text.toString() != state.text) input.edit { replace(0, length, state.text); selection = TextRange(length) }
    }
    LaunchedEffect(input) {
        snapshotFlow { input.text.toString() }.collect { if (it != externalText) updateText(it) }
    }
    Dialog(onDismissRequest = { if (!state.saving) onDismiss() }) {
        // 明确限制弹窗尺寸，让长分享文字在内容区滚动，操作栏始终可达。
        Surface(Modifier.width(360.dp).height(640.dp).testTag("source-link-dialog"),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface) {
          Column(Modifier.padding(24.dp)) {
            Text("更新链接", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前链接：${state.current.ifBlank { "未设置" }}", maxLines = 1, softWrap = false,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("source-link-current"))
                OutlinedTextField(state = input, label = { Text("分享内容或网址") }, lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 4),
                    enabled = !state.saving, modifier = Modifier.width(312.dp).height(128.dp).testTag("source-link-text"))
                OutlinedButton(onClick = onPaste, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("source-link-paste")) { Text("重新粘贴") }
                if (state.links.size > 1) Text("发现多个链接，请选择本笔记的视频链接。")
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                  state.links.forEach { url -> key(url) {
                    Row(Modifier.fillMaxWidth().selectable(selected = url == state.selected, enabled = !state.saving, role = Role.RadioButton, onClick = { onSelect(url) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = url == state.selected, onClick = null, enabled = !state.saving)
                        Text(url, Modifier.weight(1f).padding(start = 6.dp).horizontalScroll(rememberScrollState()), maxLines = 1, softWrap = false)
                    }
                  } }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("source-link-error")) }
                Text(if (preview) "确认后写入 YAML source，保存笔记后生效。" else "确认后更新 YAML source 并保存当前笔记。", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !state.saving) { Text("取消") }
                TextButton(onClick = {
                    // 输入观察尚未送达时等待候选更新，不提交上一段分享文字的地址。
                    if (input.text.toString() == state.text) onConfirm()
                }, enabled = !state.saving && state.selected != null,
                    modifier = Modifier.testTag("source-link-confirm")) { Text(if (state.saving) "正在保存…" else if (preview) "应用链接" else "更新并保存") }
            }
          }
        }
    }
}
