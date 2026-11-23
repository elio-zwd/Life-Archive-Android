package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.domain.pet.CompanionMemory
import com.elio.lifearchive.domain.pet.CompanionState

/** 卡片顺序由业务状态决定；屏幕宽度与字号只改变排布，不重排记忆。 */
@Composable
internal fun CompanionMemories(
    state: CompanionState,
    onAdd: () -> Unit,
    onEdit: (CompanionMemory) -> Unit,
    onDelete: (CompanionMemory) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(150.dp * fontScale),
        modifier = Modifier.fillMaxSize().testTag("pet-memory-grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        item(key = "intro", span = StaggeredGridItemSpan.FullLine) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.surfaceContainer, colors.surface)))
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 20.dp)) {
                    Text(state.profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Hiyori · 技术样板", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text("与你共同书写，\n珍藏重要的记忆。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Box(Modifier.width(168.dp).height(184.dp)) {
                    Sprig(Modifier.align(Alignment.BottomEnd).size(68.dp))
                    // 记忆手记页使用随包插画，人物显示不依赖 GL Surface 是否创建成功。
                    Image(painterResource(R.drawable.companion_memory_portrait_v1), contentDescription = "伙伴人物插画",
                        modifier = Modifier.fillMaxSize().testTag("pet-memory-portrait"), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
                }
            }
        }
        item(key = "heading", span = StaggeredGridItemSpan.FullLine) {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                // 换行容器在大字体与窄窗口下保持添加入口完整。
                BoxWithConstraints {
                  val singleColumn = maxWidth < 312.dp * fontScale
                  FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.padding(end = 12.dp, bottom = 8.dp)) {
                        Text("共同记忆", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Canvas(Modifier.width(112.dp).height(8.dp)) {
                            val curve = Path().apply {
                                moveTo(0f, size.height * 0.6f)
                                quadraticTo(size.width * 0.4f, 0f, size.width * 0.65f, size.height * 0.5f)
                                quadraticTo(size.width * 0.85f, size.height, size.width, size.height * 0.2f)
                            }
                            drawPath(curve, colors.primary.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        }
                    }
                    FilledTonalButton(onClick = onAdd,
                        modifier = Modifier.then(if (singleColumn) Modifier.fillMaxWidth() else Modifier).testTag("pet-memory-add")) { Text("添加记忆") }
                  }
                }
                Text("共 ${state.memories.size} 条 · 记忆可以随时更正或删除。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (state.memories.isEmpty()) item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
            Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant)) {
                Column(Modifier.padding(24.dp)) {
                    Sprig(Modifier.size(44.dp))
                    Text("把第一件小事留在这里", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text("还没有记忆。添加你希望伙伴在以后交流中记住的事，也可以在对话中选择“记住这件事”。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(state.memories, key = { _, memory -> memory.id }) { index, memory ->
            MemoryNote(memory, index, onEdit, onDelete)
        }
    }
}

@Composable
private fun MemoryNote(memory: CompanionMemory, index: Int, onEdit: (CompanionMemory) -> Unit, onDelete: (CompanionMemory) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("pet-memory-${memory.id}")) {
        Surface(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp, 19.dp, 14.dp, 18.dp),
            color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 1.dp,
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = colors.secondaryContainer.copy(alpha = 0.65f)) {
                    Text(
                        if (memory.sourceMessageId == null) "手动添加" else "来自共同对话",
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer,
                    )
                }
                Text(memory.text, Modifier.padding(top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = { onEdit(memory) },
                        modifier = Modifier.testTag("pet-memory-edit-${memory.id}").semantics { contentDescription = "更正记忆：${memory.text.take(30)}" },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text("更正") }
                    TextButton(
                        onClick = { onDelete(memory) },
                        modifier = Modifier.testTag("pet-memory-delete-${memory.id}").semantics { contentDescription = "删除记忆：${memory.text.take(30)}" },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text("删除") }
                }
            }
        }
        // 点缀不承载信息，避免读屏将胶带与枝叶作为额外控件。
        if (index % 3 == 0) Canvas(Modifier.align(Alignment.TopEnd).offset(x = (-10).dp, y = (-5).dp).size(42.dp, 18.dp)) {
            rotate(18f) { drawRect(colors.tertiaryContainer.copy(alpha = 0.65f)) }
        } else if (index % 3 == 1) Sprig(Modifier.align(Alignment.TopEnd).offset(x = (-5).dp, y = (-8).dp).size(28.dp))
    }
}

@Composable
private fun Sprig(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Canvas(modifier) {
        drawLine(color, Offset(size.width * 0.25f, size.height), Offset(size.width * 0.7f, 0f), strokeWidth = 1.dp.toPx())
        for (i in 1..4) {
            val y = size.height * i / 5f
            val x = size.width * (0.7f - 0.45f * i / 5f)
            val direction = if (i % 2 == 0) 1f else -1f
            val leaf = Path().apply {
                moveTo(x, y)
                quadraticTo(x + direction * size.width * 0.4f, y, x + direction * size.width * 0.22f, y - size.height * 0.2f)
                quadraticTo(x, y - size.height * 0.15f, x, y)
            }
            drawPath(leaf, color)
        }
    }
}
