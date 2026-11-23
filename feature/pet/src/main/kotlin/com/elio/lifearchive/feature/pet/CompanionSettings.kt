package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.domain.pet.CompanionState

@Composable
internal fun CompanionSettings(
    state: CompanionState,
    onEdit: () -> Unit,
    onVisible: (Boolean) -> Unit,
    onOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onAi: () -> Unit,
    avatar: @Composable (Modifier) -> Unit,
    onDesktopSettings: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("伙伴设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer, modifier = Modifier.testTag("pet-identity-card")) {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(colors.secondaryContainer, colors.surfaceContainerHigh))).padding(18.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(state.profile.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Hiyori · 技术样板", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall)
                    Text(state.profile.personality, Modifier.padding(top = 14.dp), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                avatar(Modifier.width(112.dp).height(184.dp))
              }
              FilledTonalButton(onClick = onEdit, modifier = Modifier.padding(top = 8.dp).testTag("pet-profile-edit")) { Text("修改名称与人格") }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("在 App 内显示伙伴", style = MaterialTheme.typography.titleMedium)
                    Text("在 Life／见域页面显示伙伴小窗。", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(state.profile.showInApp, onVisible, modifier = Modifier.testTag("pet-in-app-visible"))
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("桌面陪伴", style = MaterialTheme.typography.titleMedium)
                Text("开启后可在桌面或其他 App 上陪伴，需要悬浮窗权限。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOverlay) { Text("开启桌面陪伴") }
                    OutlinedButton(onClick = onStopOverlay) { Text("关闭") }
                    TextButton(onClick = onDesktopSettings, modifier = Modifier.testTag("pet-desktop-settings")) { Text("外观与互动") }
                }
            }
        }
        OutlinedCard(onClick = onAi, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("公共 AI", style = MaterialTheme.typography.titleMedium)
                Text("›")
            }
        }
    }
}
