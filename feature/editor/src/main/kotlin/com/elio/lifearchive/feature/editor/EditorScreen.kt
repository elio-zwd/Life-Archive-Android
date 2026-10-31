package com.elio.lifearchive.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.domain.notes.NoteId
import com.elio.lifearchive.core.preferences.DocumentTheme
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.designsystem.colors
import kotlinx.coroutines.launch

@Composable
fun EditorRoute(model: EditorViewModel, fontScale: Float, debugControls: Boolean, onBack: () -> Unit, onOpenCopy: (NoteId) -> Unit, services: ReaderServices? = null, onLink: ((String) -> Unit)? = null, onAttach: () -> Unit = {}, onManage: () -> Unit = {}, documentTheme: DocumentTheme = DocumentTheme.BLUE, onDocumentTheme: (DocumentTheme) -> Unit = {}, onCover: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val coordinator = model.persistence
    val tools = remember(model) { EditorToolActions(model::command, model::beginInsertion, model::dismissInsertion, model::reanchorInsertion, model::editInsertion, model::searchLinkNotes, model::insertExternal, model::insertTemplateFromPrompt, model::insertNoteLink, model::reinsertAttachment) }
    val status = if (coordinator == null) SaveStatus() else coordinator.status.collectAsStateWithLifecycle().value
    val lifecycle = LocalLifecycleOwner.current
    BackHandler { model.leave(onBack) }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.backgroundSave() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    EditorScreen(
        state, model.session, status, fontScale, debugControls,
        onBack = { model.leave(onBack) }, onMode = model::changeMode,
        onRetry = model::backgroundSave, onRestore = model::restoreDraft,
        onCopy = { model.copyDraft(onOpenCopy) }, onFailureSimulation = model::setSimulatedFailure,
        initialReadingOffset = model.initialReadingOffset, onReadingOffset = model::readingPosition,
        loadImage = model::image, onLink = onLink ?: { model.message("链接目标：$it") }, services = services,
        onAttach = onAttach, onManage = onManage, toolActions = tools, documentTheme = documentTheme, onDocumentTheme = onDocumentTheme, onCover = onCover,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState, session: EditorSession?, save: SaveStatus, fontScale: Float = 1f,
    debugControls: Boolean = false, onBack: () -> Unit = {}, onMode: (EditorMode) -> Unit = {},
    onRetry: () -> Unit = {}, onRestore: () -> Unit = {}, onCopy: () -> Unit = {}, onFailureSimulation: (Boolean) -> Unit = {},
    initialReadingOffset: Int = 0, onReadingOffset: (Int) -> Unit = {},
    loadImage: suspend (String) -> ByteArray? = { null }, onLink: (String) -> Unit = {},
    editorEngine: EditorEngine = ComposeEditorEngine, readerRenderer: ReaderRenderer = WebReaderRenderer,
    services: ReaderServices? = null, onAttach: () -> Unit = {}, onManage: () -> Unit = {},
    toolActions: EditorToolActions? = null, documentTheme: DocumentTheme = DocumentTheme.BLUE, onDocumentTheme: (DocumentTheme) -> Unit = {},
    onCover: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var modes by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var themes by remember { mutableStateOf(false) }
    var headingLevels by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val editing = state.mode != EditorMode.READ
    val navigation = remember(session) { ReaderNavigation() }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    navigationIcon = { HeaderButton("返回", HeaderGlyph.BACK, onBack, enabled = !state.busy) },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onManage)) {
                            Text(state.name.substringAfterLast('/').ifBlank { "笔记" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            if (editing) Text(save.label + if (save.indexPending) " · 索引待更新" else "", color = if (save.error != null) colors.error else colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        if (editing) Box {
                            TextButton(onClick = { modes = true }) { Text(if (state.mode == EditorMode.LIVE) "实时预览" else state.mode.label, style = MaterialTheme.typography.labelMedium) }
                            DropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
                                EditorMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label) }, enabled = state.pendingDraft == null || mode == EditorMode.READ, onClick = { modes = false; onMode(mode); if (mode == EditorMode.READ) keyboard?.hide() }) }
                            }
                        }
                        else HeaderButton("目录", HeaderGlyph.OUTLINE, onClick = { navigation.refresh(); outline = true })
                        Box {
                            HeaderButton("更多", HeaderGlyph.MORE, onClick = { more = true })
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text("主题") }, onClick = { more = false; themes = true })
                                DropdownMenuItem(text = { Text("设置封面") }, enabled = !state.loading && !state.busy, onClick = { more = false; keyboard?.hide(); onCover() })
                                if (editing) DropdownMenuItem(text = { Text("选择标题级别") }, enabled = state.pendingDraft == null && session != null && session.canModify && !state.busy && !state.commandRunning, onClick = { more = false; headingLevels = true })
                                if (!editing) DropdownMenuItem(text = { Text("编辑笔记") }, enabled = session != null && state.pendingDraft == null, onClick = { more = false; onMode(EditorMode.SOURCE) })
                                if (editing) DropdownMenuItem(text = { Text("插入附件") }, enabled = state.pendingDraft == null && !state.busy && !state.commandRunning && session?.canModify == true, onClick = { more = false; onAttach() })
                                DropdownMenuItem(text = { Text("笔记信息与管理") }, onClick = { more = false; onManage() })
                                if (debugControls) DropdownMenuItem(text = { Text(if (failure) "关闭保存失败模拟" else "模拟正文保存失败") }, onClick = { failure = !failure; onFailureSimulation(failure); more = false })
                            }
                        }
                    },
                )
                HorizontalDivider(color = colors.outlineVariant)
            }
        },
        bottomBar = {
            if (editing && session != null) EditorToolbar(session, !state.busy && !state.commandRunning && state.pendingDraft == null, state.projection?.document ?: state.parsed,
                onCommand = { command, anchor -> if (toolActions != null) toolActions.command(command, anchor) else { session.command(command, anchor); Unit } },
                onInsert = toolActions?.begin ?: {}, onAttach = onAttach)
        },
        floatingActionButton = { if (!editing && session != null && state.pendingDraft == null) ExtendedFloatingActionButton(onClick = { onMode(EditorMode.SOURCE) }, containerColor = colors.primary, contentColor = colors.onPrimary, shape = RoundedCornerShape(24.dp)) { Text("✎  编辑", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { contentDescription = "编辑笔记" }) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.error != null) Text(state.error, color = colors.error, modifier = Modifier.padding(22.dp))
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.pendingDraft != null) Surface(color = colors.surfaceContainerHighest) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(if (state.draftConflict) "发现基于旧版本的草稿，原笔记保持不变" else "发现未保存草稿，确认后可继续编辑", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        if (!state.draftConflict) TextButton(onClick = onRestore) { Text("恢复草稿") }
                        TextButton(onClick = onCopy, enabled = !state.busy) { Text("另存草稿") }
                        Text("可暂时阅读原笔记", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
            if (save.error != null) {
                Text(save.error + if (save.draftProtected) " · 草稿已保护" else " · 草稿尚未安全落盘，请勿结束应用", color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                TextButton(onClick = onRetry) { Text("重试") }
                if (save.conflict) TextButton(onClick = onCopy, enabled = !state.busy) { Text("将当前内容另存为新笔记") }
            }
            if (state.message != null) Text(state.message, style = MaterialTheme.typography.bodySmall, color = colors.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (state.attachmentToInsert != null) TextButton(onClick = { toolActions?.reinsertAttachment?.invoke() }, enabled = editing && !state.busy && !state.commandRunning && session?.canModify == true) { Text("将附件插入当前光标") }
            if (state.mode == EditorMode.LIVE && session != null && session.text.text.length > FULL_PREVIEW_LIMIT) Text("长文实时样式仅覆盖光标附近，完整原文仍可编辑", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Box(Modifier.weight(1f)) {
                if (session != null) {
                    if (editing) {
                        // 高亮准备不能阻塞输入连接，也不能在空笔记刚输入时卸载文本控件。
                        editorEngine.Content(session, state.mode, state.projection?.document ?: state.parsed, fontScale, requestFocus = true, enabled = !state.busy)
                        if (state.parsed == null && session.text.text.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val raw = session.content.source
                        val snapshot = remember(session, raw, state.path) { ReaderSnapshot(raw, path = state.path, version = state.version) }
                        readerRenderer.Content(snapshot, fontScale, initialReadingOffset, onReadingOffset, loadImage, onLink, services?.copy(navigation = navigation))
                    }
                }
            }
        }
    }
    if (toolActions != null) EditorInsertionDialog(state, toolActions)
    if (themes) DocumentThemeDialog(documentTheme, onDismiss = { themes = false }, onChoose = { themes = false; onDocumentTheme(it) })
    if (headingLevels && session != null) HeadingLevelDialog(session, onDismiss = { headingLevels = false; session.requestInputFocus() }, onCommand = { command, anchor -> if (toolActions != null) toolActions.command(command, anchor) else session.command(command, anchor) })
    if (outline) AlertDialog(
        onDismissRequest = { outline = false }, title = { Text("目录") },
        text = {
            if (navigation.headings.isEmpty()) Text("暂无可定位标题") else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(navigation.headings, key = { "${it.offset}:${it.level}" }) { heading ->
                    TextButton(onClick = { outline = false; navigation.moveTo(heading.offset) }, modifier = Modifier.fillMaxWidth().padding(start = ((heading.level - 1) * 12).dp)) {
                        Text(heading.title, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { outline = false }) { Text("关闭") } },
    )
}

private enum class HeaderGlyph { BACK, MORE, OUTLINE }

@Composable
private fun HeaderButton(label: String, glyph: HeaderGlyph, onClick: () -> Unit, enabled: Boolean = true) {
    val color = MaterialTheme.colorScheme.onSurface
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Canvas(Modifier.size(22.dp)) {
            val sx = size.width / 24f; val sy = size.height / 24f; val stroke = 1.8f * sx
            when (glyph) {
                HeaderGlyph.BACK -> {
                    val path = Path().apply { moveTo(15 * sx, 5 * sy); lineTo(8 * sx, 12 * sy); lineTo(15 * sx, 19 * sy) }
                    drawPath(path, color, style = Stroke(width = stroke))
                }
                HeaderGlyph.MORE -> listOf(5f, 12f, 19f).forEach { drawCircle(color, radius = 1.4f * sx, center = Offset(12 * sx, it * sy)) }
                HeaderGlyph.OUTLINE -> {
                    drawRoundRect(color, topLeft = Offset(5 * sx, 3 * sy), size = androidx.compose.ui.geometry.Size(14 * sx, 18 * sy), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * sx), style = Stroke(width = stroke))
                    listOf(8f, 12f, 16f).forEach { drawLine(color, Offset(8 * sx, it * sy), Offset(16 * sx, it * sy), stroke) }
                }
            }
        }
    }
}

@Composable
private fun DocumentThemeDialog(current: DocumentTheme, onDismiss: () -> Unit, onChoose: (DocumentTheme) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val choices = listOf(
        Triple(DocumentTheme.BLUE, "A · 浅灰蓝", "极简写作 · 轻量蓝色工具"),
        Triple(DocumentTheme.WARM, "B · 暖纸色", "温润手记 · 柔和灰绿强调"),
        Triple(DocumentTheme.GRAPHITE, "C · 深石墨", "专注阅读 · 低亮度深色画布"),
    )
    AlertDialog(onDismissRequest = onDismiss, title = { Text("主题") }, text = {
        Column {
            Text("阅读与编辑共用主题，首页外观保持独立。", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 14.dp))
            choices.forEach { (theme, label, description) ->
                val selected = current == theme
                val palette = when (theme) { DocumentTheme.BLUE -> DocumentPalette.BLUE; DocumentTheme.WARM -> DocumentPalette.WARM; DocumentTheme.GRAPHITE -> DocumentPalette.GRAPHITE }.colors
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
                            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(description, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (selected) Text("✓", color = scheme.primary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
