package com.elio.lifearchive.feature.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReaderSnapshot(val source: String, val revision: String = java.util.UUID.randomUUID().toString(), val path: String? = null, val version: String? = null) {
    // 由读取投影的后台任务首次计算，不在 Compose 重组中遍历长文。
    val digest: String by lazy { com.elio.lifearchive.core.document.documentDigest(source) }
}

typealias ReaderLoadPhase = com.elio.lifearchive.core.markdown.MarkdownLoadPhase
typealias ReaderLoadEvent = com.elio.lifearchive.core.markdown.MarkdownLoadEvent
interface ReaderRenderer {
    @Composable fun Content(document: ReaderSnapshot, fontScale: Float, initialOffset: Int, onOffset: (Int) -> Unit, loadImage: suspend (String) -> ByteArray?, onLink: (String) -> Unit, services: ReaderServices? = null)
}

object ComposeReaderRenderer : ReaderRenderer {
    @Composable override fun Content(document: ReaderSnapshot, fontScale: Float, initialOffset: Int, onOffset: (Int) -> Unit, loadImage: suspend (String) -> ByteArray?, onLink: (String) -> Unit, services: ReaderServices?) {
        val blocks = remember(document.source) { MarkdownParser().parse(document.source).blocks }
        val index = blocks.indexOfLast { it.start <= initialOffset }.coerceAtLeast(0)
        val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = index)
        val currentOnOffset by rememberUpdatedState(onOffset)
        LaunchedEffect(state, blocks) {
            snapshotFlow { state.firstVisibleItemIndex }.collect { currentOnOffset(blocks.getOrNull(it)?.start ?: 0) }
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(blocks, key = { i, block -> "${block.start}-$i" }) { _, block ->
                ReaderBlockView(block, fontScale, loadImage, onLink)
            }
            item { Text("本轮为交互样板 · 复杂语法支持情况见验收说明", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp)) }
        }
    }
}

@Composable
private fun styledRuns(runs: List<InlineRun>, onLink: (String) -> Unit): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return buildAnnotatedString {
        for (run in runs) {
            val style = SpanStyle(
                fontWeight = if (TextMark.BOLD in run.marks) FontWeight.Bold else null,
                fontStyle = if (TextMark.ITALIC in run.marks) FontStyle.Italic else null,
                fontFamily = if (TextMark.CODE in run.marks) FontFamily.Monospace else null,
                background = if (TextMark.CODE in run.marks) colors.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (TextMark.STRIKE in run.marks) TextDecoration.LineThrough else null,
            )
            withStyle(style) {
                val destination = run.destination
                if (destination == null) append(run.text)
                else withLink(LinkAnnotation.Clickable(destination, TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) { onLink(destination) }) { append(run.text) }
            }
        }
    }
}

@Composable
private fun ReaderBlockView(block: ReaderBlock, scale: Float, loadImage: suspend (String) -> ByteArray?, onLink: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = (17 * scale).sp, lineHeight = (29 * scale).sp)
    when (block.kind) {
        BlockKind.HEADING -> SelectionContainer {
            Text(styledRuns(block.runs, onLink), style = bodyStyle.copy(fontSize = ((29 - block.level.coerceAtMost(5) * 2) * scale).sp, lineHeight = (35 * scale).sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
        }
        BlockKind.PARAGRAPH -> SelectionContainer { Text(styledRuns(block.runs, onLink), style = bodyStyle, modifier = Modifier.padding(start = (block.level * 8).dp)) }
        BlockKind.QUOTE, BlockKind.CALLOUT -> Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary))
                Column(Modifier.padding(14.dp)) {
                    if (block.kind == BlockKind.CALLOUT) Text(block.label, color = colors.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                    SelectionContainer { Text(styledRuns(block.runs, onLink), style = bodyStyle, color = colors.onSurfaceVariant) }
                }
            }
        }
        BlockKind.CODE, BlockKind.RAW -> Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(block.label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SelectionContainer { Text(block.raw.trimEnd('\n', '\r'), style = bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = (14 * scale).sp, lineHeight = (23 * scale).sp), modifier = Modifier.horizontalScroll(rememberScrollState())) }
            }
        }
        BlockKind.PROPERTIES -> {
            var expanded by remember { mutableStateOf(false) }
            OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (expanded) "▾ ${block.label}" else "▸ ${block.label}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    if (expanded) { Spacer(Modifier.height(10.dp)); SelectionContainer { Text(block.raw, fontFamily = FontFamily.Monospace, fontSize = (13 * scale).sp) }; Text("切换源码模式可编辑全部属性", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        BlockKind.TABLE -> Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainer) {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(1.dp)) {
                block.rows.forEachIndexed { rowIndex, row ->
                    Row(Modifier.background(if (rowIndex == 0) colors.surfaceContainerHighest else colors.surfaceContainer)) {
                        row.forEach { cell -> Text(cell, style = bodyStyle.copy(fontSize = (14 * scale).sp, fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal), modifier = Modifier.width(150.dp).padding(12.dp)) }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
        }
        BlockKind.IMAGE -> {
            val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, block.destination) {
                value = withContext(Dispatchers.IO) {
                    loadImage(block.destination)?.let { bytes ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2 }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                    }
                }
            }
            Column {
                if (image != null) Image(image!!, contentDescription = block.label, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                else Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium) { Text("图片不可用或仍在加载：${block.destination}", style = bodyStyle, modifier = Modifier.fillMaxWidth().padding(22.dp)) }
                Text(block.label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 7.dp))
            }
        }
        BlockKind.DIVIDER -> HorizontalDivider(color = colors.outlineVariant)
    }
}
