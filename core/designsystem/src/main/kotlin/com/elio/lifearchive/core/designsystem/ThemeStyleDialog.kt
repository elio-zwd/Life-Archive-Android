package com.elio.lifearchive.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val DocumentPalette.label: String get() = when (this) {
    DocumentPalette.BLUE -> "A · 浅灰蓝"
    DocumentPalette.WARM -> "B · 暖纸色"
    DocumentPalette.GRAPHITE -> "C · 深石墨"
}

@Composable
fun ThemeStyleDialog(current: DocumentPalette, onDismiss: () -> Unit, onChoose: (DocumentPalette) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(onDismissRequest = onDismiss, title = { Text("主题") }, text = {
        Column {
            Text("首页、阅读与编辑共用一个主题。", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 14.dp))
            DocumentPalette.entries.forEach { theme ->
                val selected = current == theme
                val palette = theme.colors
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).selectable(selected = selected, role = Role.RadioButton, onClick = { onChoose(theme) }),
                    shape = RoundedCornerShape(12.dp), color = if (selected) scheme.primaryContainer else scheme.surface,
                    border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(8.dp), color = palette.background, border = BorderStroke(1.dp, palette.line)) {
                            Box(Modifier.size(width = 44.dp, height = 50.dp), contentAlignment = Alignment.Center) {
                                Text("Aa", color = palette.accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(theme.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(when (theme) {
                                DocumentPalette.BLUE -> "极简写作 · 轻量蓝色工具"
                                DocumentPalette.WARM -> "温润手记 · 柔和灰绿强调"
                                DocumentPalette.GRAPHITE -> "专注阅读 · 低亮度深色画布"
                            }, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (selected) Text("✓", color = scheme.primary, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
