package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.domain.pet.*

@Composable
internal fun DesktopCompanionSettings(value: OverlaySettings, dismiss: () -> Unit, save: (OverlaySettings) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("桌面陪伴设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("角色大小 · " + (draft.heightFraction * 100).toInt() + "%")
            Slider(draft.heightFraction, { draft = draft.copy(heightFraction = it) }, valueRange = .15f.. .4f)
            Text("主动短句")
            GreetingMode.entries.forEach { mode ->
                FilterChip(selected = draft.greetingMode == mode, onClick = { draft = draft.copy(greetingMode = mode) }, label = { Text(when (mode) {
                    GreetingMode.OFF -> "关闭"; GreetingMode.LOCAL -> "本地"; GreetingMode.LOCAL_AI -> "本地＋AI"
                }) })
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
