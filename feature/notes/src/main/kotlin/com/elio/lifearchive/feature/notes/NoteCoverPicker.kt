package com.elio.lifearchive.feature.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.core.designsystem.CoverArtwork
import com.elio.lifearchive.core.document.DocumentAnalyzer
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCoverPicker(
    note: LibraryNote, library: LibraryRepository, imported: CoverImage? = null,
    busy: Boolean = false, error: String? = null, onPickImage: () -> Unit,
    onDismiss: () -> Unit, onSave: (NoteCover) -> Unit,
    notes: NotesRepository? = null,
) {
    var selected by remember(note.summary.id) { mutableStateOf(note.cover) }
    var custom by remember(note.summary.id) { mutableStateOf(note.cover is NoteCover.Attachment) }
    val images by produceState<List<CoverImage>>(emptyList(), note.summary.id, library, notes) {
        suspend fun refresh() { value = withContext(Dispatchers.IO) { (library.coverImages(note.summary.id) as? NoteResult.Success)?.value.orEmpty() } }
        // 面板打开后自动保存仍可完成，新保存的正文图片随通知出现，不干预编辑会话。
        if (notes == null) refresh() else notes.observeNotes().collectLatest { refresh() }
    }
    LaunchedEffect(imported) { imported?.let { selected = NoteCover.Attachment(it.path); custom = true } }
    val candidates = remember(images, imported, note.cover) {
        (listOfNotNull(imported, (note.cover as? NoteCover.Attachment)?.let { CoverImage(it.path, "当前封面") }) + images).distinctBy { it.path }
    }
    // 横幅预览单独采样，首页仍使用 256px／4 MiB 的小图预算。
    val loader = remember(library) { HomeThumbnailLoader(library, maxDimension = 1024, cacheBytes = 8 * 1024 * 1024) }
    DisposableEffect(loader) { onDispose { loader.retire() } }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 22.dp).testTag("cover-picker")) {
            Text("设置封面", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("预设", "自定义").forEachIndexed { index, label ->
                    SegmentedButton(selected = custom == (index == 1), enabled = !busy,
                        onClick = { custom = index == 1 }, shape = SegmentedButtonDefaults.itemShape(index, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.primary, activeBorderColor = MaterialTheme.colorScheme.primary,
                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant, inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant)) { Text(label) }
                }
            }
            Spacer(Modifier.height(16.dp))
            PickerArtwork(note.summary.id, selected, loader, Modifier.fillMaxWidth().aspectRatio(2.4f).clip(RoundedCornerShape(12.dp)).testTag("cover-preview"))
            Text("首页与阅读页共用封面", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!custom) items(CoverPreset.entries.chunked(2)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { preset ->
                            val cover = NoteCover.Preset(preset)
                            PickerChoice(preset.label, selected == cover, !busy, { selected = cover }, Modifier.weight(1f)) {
                                PickerArtwork(note.summary.id, cover, loader, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(9.dp)))
                            }
                        }
                    }
                } else {
                    item { OutlinedButton(onClick = onPickImage, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在导入…" else "从手机选择图片") } }
                    if (candidates.isEmpty()) item { Text("笔记中还没有本地图片，可从手机选择一张。", style = MaterialTheme.typography.bodyMedium) }
                    items(candidates.chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { image ->
                                val cover = NoteCover.Attachment(image.path)
                                PickerChoice(image.label, selected == cover, !busy, { selected = cover }, Modifier.weight(1f)) {
                                    PickerArtwork(note.summary.id, cover, loader, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(9.dp)))
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { onSave(selected) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("cover-save")) { Text(if (busy) "处理中…" else "保存") }
            }
        }
    }
}

@Composable
private fun PickerChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, artwork: @Composable () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            artwork()
            Text((if (selected) "✓  " else "") + label, modifier = Modifier.padding(7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PickerArtwork(id: NoteId, cover: NoteCover, loader: HomeThumbnailLoader, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, id, cover, loader) {
        value = null
        if (cover is NoteCover.Attachment) value = try { loader.load(id, "/" + DocumentAnalyzer.encode(cover.path.value), LinkSyntax.WIKI) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    CoverArtwork((cover as? NoteCover.Preset)?.preset?.key ?: "mountain", image, modifier, maxDimension = 1024)
}
