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
import com.elio.lifearchive.core.live2d.Live2DModelCatalog
import com.elio.lifearchive.core.live2d.Live2DModelSpec
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
    onSelectModel: (String) -> Unit = {},
    onImportModel: () -> Unit = {},
    onDeleteModel: (Live2DModelSpec) -> Unit = {},
    importingModel: Boolean = false,
    modelNotice: String? = null,
    models: List<Live2DModelSpec> = syncLive2DCatalog(state.importedModels),
) {
    val colors = MaterialTheme.colorScheme
    val currentModel = models.firstOrNull { it.id == state.profile.modelId } ?: Live2DModelCatalog.resolve(state.profile.modelId)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("伙伴设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer, modifier = Modifier.testTag("pet-identity-card")) {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(colors.secondaryContainer, colors.surfaceContainerHigh))).padding(18.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(state.profile.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${currentModel.displayName} · ${currentModel.subtitle}", Modifier.padding(top = 6.dp).testTag("pet-current-model"), style = MaterialTheme.typography.labelSmall)
                    Text(state.profile.personality, Modifier.padding(top = 14.dp), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                avatar(Modifier.width(112.dp).height(184.dp))
              }
              FilledTonalButton(onClick = onEdit, modifier = Modifier.padding(top = 8.dp).testTag("pet-profile-edit")) { Text("修改名称与人格") }
            }
        }
        Card(Modifier.fillMaxWidth().testTag("pet-model-card"), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("角色形象", style = MaterialTheme.typography.titleMedium)
                Text(
                    "通用 Live2D 模型容器 · 共 ${models.size} 个可用模型，切换后实时同步到陪伴页、小窗与桌面悬浮。",
                    Modifier.padding(top = 6.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    models.forEach { spec ->
                        FilterChip(
                            selected = spec.id == currentModel.id,
                            onClick = { onSelectModel(spec.id) },
                            modifier = Modifier.testTag("pet-model-${spec.id}"),
                            label = { Text(spec.displayName) },
                        )
                    }
                }
                FlowRow(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(
                        onClick = onImportModel,
                        enabled = !importingModel,
                        modifier = Modifier.testTag("pet-model-import"),
                    ) {
                        Text(if (importingModel) "导入中…" else "导入本地模型")
                    }
                    if (!currentModel.isBuiltIn) {
                        OutlinedButton(
                            onClick = { onDeleteModel(currentModel) },
                            enabled = !importingModel,
                            modifier = Modifier.testTag("pet-model-delete"),
                        ) {
                            Text("移除当前导入")
                        }
                    }
                }
                Text(
                    "选择手机本地包含 .model3.json、.moc3 与纹理的文件夹，将自动复制到应用私有目录。",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                modelNotice?.let { notice ->
                    Text(
                        notice,
                        Modifier.padding(top = 6.dp).testTag("pet-model-notice"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.primary,
                    )
                }
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
