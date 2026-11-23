package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.domain.pet.*

@Composable
internal fun DesktopCompanionSettings(value: OverlaySettings, dismiss: () -> Unit, save: (OverlaySettings) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("桌面陪伴设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("角色大小 · " + (draft.heightFraction * 100).toInt() + "%")
            Slider(draft.heightFraction, { draft = draft.copy(heightFraction = it) }, valueRange = .15f.. .4f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("5 秒无操作自动穿透", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "穿透后点击直达底层应用并展开全宽画布；可在通知栏点「唤醒互动」或轻摇手机恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.autoPassthrough,
                    onCheckedChange = { draft = draft.copy(autoPassthrough = it) },
                    modifier = Modifier.testTag("pet-overlay-passthrough"),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("模型支持时自动走动", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "仅当模型自带走动／跑步动作时横向来回走动，无该动作的模型保持原地。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.autoWalk,
                    onCheckedChange = { draft = draft.copy(autoWalk = it) },
                    modifier = Modifier.testTag("pet-overlay-autowalk"),
                )
            }
            HorizontalDivider()
            Text("主动短句")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GreetingMode.entries.forEach { mode ->
                    FilterChip(selected = draft.greetingMode == mode, onClick = { draft = draft.copy(greetingMode = mode) }, label = { Text(when (mode) {
                        GreetingMode.OFF -> "关闭"; GreetingMode.LOCAL -> "本地"; GreetingMode.LOCAL_AI -> "本地＋AI"
                    }) })
                }
            }
            Text("只在开启陪伴、久别后解锁时问候，最多每天四次。", style = MaterialTheme.typography.bodySmall)
            Text("安静时段（起止相同表示关闭）")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HourPicker("开始", draft.quietStartHour) { draft = draft.copy(quietStartHour = it) }
                HourPicker("结束", draft.quietEndHour) { draft = draft.copy(quietEndHour = it) }
            }
        }
    }, confirmButton = { TextButton(onClick = { save(draft); dismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun HourPicker(label: String, hour: Int, change: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label $hour:00") }
        DropdownMenu(expanded, { expanded = false }) {
            (0..23).forEach { value -> DropdownMenuItem(text = { Text("$value:00") }, onClick = { change(value); expanded = false }) }
        }
    }
}
