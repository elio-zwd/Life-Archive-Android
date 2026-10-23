package com.elio.lifearchive.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.domain.notes.NoteId
import kotlinx.coroutines.launch

@Composable
fun EditorRoute(model: EditorViewModel, fontScale: Float, debugControls: Boolean, onBack: () -> Unit, onOpenCopy: (NoteId) -> Unit, services: ReaderServices? = null, onLink: ((String) -> Unit)? = null, onAttach: () -> Unit = {}, onManage: () -> Unit = {}) {
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
        onAttach = onAttach, onManage = onManage, toolActions = tools,
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
    toolActions: EditorToolActions? = null,
) {
    val colors = MaterialTheme.colorScheme
    var modes by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val editing = state.mode != EditorMode.READ
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { TextButton(onClick = onBack, enabled = !state.busy) { Text("返回") } },
                    title = { TextButton(onClick = onManage) { Text(state.name.ifBlank { "笔记" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) } },
                    actions = {
                        TextButton(onClick = onAttach, enabled = state.pendingDraft == null && !state.busy && !state.commandRunning && session?.canModify == true) { Text("附件") }
                        Box {
                            TextButton(onClick = { modes = true }) { Text(if (state.mode == EditorMode.LIVE) "实时预览" else state.mode.label) }
                            DropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
                                EditorMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label) }, enabled = state.pendingDraft == null || mode == EditorMode.READ, onClick = { modes = false; onMode(mode); if (mode == EditorMode.READ) keyboard?.hide() }) }
                            }
                        }
                        if (debugControls) Box {
                            TextButton(onClick = { more = true }) { Text("更多") }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text(if (failure) "关闭保存失败模拟" else "模拟正文保存失败") }, onClick = { failure = !failure; onFailureSimulation(failure); more = false })
                            }
                        }
                    },
                )
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(save.label + if (save.indexPending) " · 索引待更新" else "", color = if (save.error != null) colors.error else colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (save.error != null) TextButton(onClick = onRetry) { Text("重试") }
                    else Text("本地笔记", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                HorizontalDivider(color = colors.outlineVariant)
            }
        },
        bottomBar = {
            if (editing && session != null) EditorToolbar(session, !state.busy && !state.commandRunning && state.pendingDraft == null, state.projection?.document ?: state.parsed,
                onCommand = { command, anchor -> if (toolActions != null) toolActions.command(command, anchor) else { session.command(command, anchor); Unit } },
                onInsert = toolActions?.begin ?: {}, onAttach = onAttach)
        },
        floatingActionButton = { if (!editing && session != null && state.pendingDraft == null) ExtendedFloatingActionButton(onClick = { onMode(EditorMode.SOURCE) }) { Text("编辑笔记") } },
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
                if (save.conflict) TextButton(onClick = onCopy, enabled = !state.busy) { Text("将当前内容另存为新笔记") }
            }
            if (state.message != null) Text(state.message, style = MaterialTheme.typography.bodySmall, color = colors.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (state.attachmentToInsert != null) TextButton(onClick = { toolActions?.reinsertAttachment?.invoke() }, enabled = editing && !state.busy && !state.commandRunning && session?.canModify == true) { Text("将附件插入当前光标") }
            if (state.mode == EditorMode.LIVE && session != null && session.text.text.length > FULL_PREVIEW_LIMIT) Text("长文实时样式仅覆盖光标附近，完整原文仍可编辑", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Box(Modifier.weight(1f)) {
                if (session != null) {
                    if (editing) {
                        if (state.parsed != null || session.text.text.isEmpty()) editorEngine.Content(session, state.mode, state.projection?.document ?: state.parsed, fontScale, requestFocus = true, enabled = !state.busy)
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val raw = session.text.text.toString()
                        val snapshot = remember(session, raw, state.path) { ReaderSnapshot(raw, path = state.path, version = state.version) }
                        readerRenderer.Content(snapshot, fontScale, initialReadingOffset, onReadingOffset, loadImage, onLink, services)
                    }
                }
            }
        }
    }
    if (toolActions != null) EditorInsertionDialog(state, toolActions)
}
