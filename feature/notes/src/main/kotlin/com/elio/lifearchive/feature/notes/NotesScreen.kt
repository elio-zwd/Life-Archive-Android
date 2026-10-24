package com.elio.lifearchive.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.domain.notes.NoteId
import com.elio.lifearchive.domain.notes.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    state: NotesUiState, onOpen: (NoteId) -> Unit, onCreate: (String, (NoteId) -> Unit) -> Unit,
    onCreateSample: () -> Unit, onTheme: () -> Unit, themeLabel: String, onClearError: () -> Unit,
    sampleBusy: Boolean = false, message: String? = null,
    onCreated: (NoteId) -> Unit = onOpen,
    onQuery: (NotesQuery) -> Unit = {}, onSettings: () -> Unit = {}, onQuick: () -> Unit = {},
    onManage: (LibraryNote) -> Unit = {}, onFolder: () -> Unit = {}, onRebuild: () -> Unit = {}, onFolderManage: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val presentation = remember(state.notes, state.listing) { noteListPresentation(state.notes, state.listing) }
    // 上层回调会捕获整页状态；行回调身份保持稳定，执行时仍使用最新目标。
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnManage by rememberUpdatedState(onManage)
    val open: (NoteId) -> Unit = remember { { id -> currentOnOpen(id) } }
    val manage: (NoteId) -> Unit = remember(presentation) { { id -> presentation.details[id]?.let(currentOnManage); Unit } }
    val folders = remember(state.listing?.folders) { (listOf("全部笔记本") + state.listing?.folders.orEmpty()).distinct() }
    val tags = remember(state.listing?.tags) { (listOf("全部标签") + state.listing?.tags.orEmpty()).distinct() }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("Life Archive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text("本地笔记", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) } }, actions = { TextButton(onClick = onTheme) { Text(themeLabel) }; TextButton(onClick = onSettings) { Text("设置") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { naming = true; onClearError() }) { Text("＋  新建笔记") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("notes-list"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "heading", contentType = "heading") {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("全部笔记", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text("${state.notes.size} 篇", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (state.listing != null) item(key = "filters", contentType = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = state.query.text, onValueChange = { onQuery(state.query.copy(text = it)) }, label = { Text("搜索文件名、标题与正文") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceMenu(state.query.folder ?: "全部笔记本", folders) { onQuery(state.query.copy(folder = it.takeUnless { it == "全部笔记本" })) }
                        ChoiceMenu(state.query.tag ?: "全部标签", tags) { onQuery(state.query.copy(tag = it.takeUnless { it == "全部标签" })) }
                    }
                    Row {
                        ChoiceMenu(when (state.query.sort) { NoteSort.MODIFIED_DESC -> "最近修改"; NoteSort.MODIFIED_ASC -> "较早修改"; NoteSort.CREATED_DESC -> "最近创建"; NoteSort.CREATED_ASC -> "较早创建"; NoteSort.NAME -> "名称" }, listOf("最近修改", "较早修改", "最近创建", "较早创建", "名称")) { value -> onQuery(state.query.copy(sort = NoteSort.entries[listOf("最近修改", "较早修改", "最近创建", "较早创建", "名称").indexOf(value)])) }
                        TextButton(onClick = { onQuery(state.query.copy(trash = !state.query.trash)) }) { Text(if (state.query.trash) "返回笔记" else "回收站") }
                        TextButton(onClick = onQuick, enabled = !state.query.trash) { Text("快速记录") }
                    }
                    Row { TextButton(onClick = onFolder) { Text("新建笔记本") }; if (state.query.folder != null) TextButton(onClick = onFolderManage) { Text("移动笔记本") } }
                    if (state.listing.pendingIndex > 0) Row { Text("${state.listing.pendingIndex} 篇索引待更新", modifier = Modifier.weight(1f)); TextButton(onClick = onRebuild) { Text("重试索引") } }
                }
            }
            if (state.loading) item(key = "loading", contentType = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.updating && !state.loading) item(key = "updating", contentType = "status") { Text("正在更新列表…", style = MaterialTheme.typography.labelSmall) }
            state.progress?.let { progress -> item(key = "progress", contentType = "progress") { Text("${progress.operation} ${progress.completed}/${progress.total}"); LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth()) } }
            if (state.error != null && !naming) item(key = "error", contentType = "status") { Text(state.error, color = colors.error) }
            if (state.error != null && !naming) item(key = "retry", contentType = "action") { TextButton(onClick = onRebuild) { Text("重建索引并重试") } }
            if (message != null) item(key = "message", contentType = "status") { Text(message, color = colors.primary, style = MaterialTheme.typography.bodySmall) }
            if (!state.loading && state.notes.isEmpty()) item(key = "empty", contentType = "empty") {
                Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text("从一篇笔记开始", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        Text("给它一个名字，再慢慢写下想留下的事。笔记保存在此设备中。", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            items(presentation.rows, key = { "note:${it.id}" }, contentType = { "note" }) { row ->
                NoteRow(row, enabled = !state.updating, onOpen = open, onManage = manage)
            }
            item(key = "samples", contentType = "samples") {
                Column(Modifier.padding(top = 18.dp, bottom = 90.dp)) {
                    Text("验收资料", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    TextButton(onClick = onCreateSample, enabled = !sampleBusy) { Text(if (sampleBusy) "正在创建…" else "创建验收样例") }
                    Text("仅点击后才创建合成笔记和图片。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
    if (naming) AlertDialog(
        onDismissRequest = { if (!state.creating) naming = false },
        title = { Text("新建笔记") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it; onClearError() }, label = { Text("笔记名（不含 .md）") }, singleLine = true, enabled = !state.creating, isError = state.error != null, modifier = Modifier.testTag("note-name"))
                if (state.error != null) Text(state.error, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Text("文件名与正文标题、YAML title 各自独立。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(enabled = !state.creating, onClick = { onCreate(name) { id -> naming = false; name = ""; onCreated(id) } }) { Text(if (state.creating) "创建中…" else "创建") } },
        dismissButton = { TextButton(enabled = !state.creating, onClick = { naming = false; name = ""; onClearError() }) { Text("取消") } },
    )
}

@Composable
private fun NoteRow(row: NoteRowUi, enabled: Boolean, onOpen: (NoteId) -> Unit, onManage: (NoteId) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("note:${row.id}").clickable(enabled = enabled) { onOpen(NoteId(row.id)) }) {
        Column(Modifier.padding(18.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.tags.isNotEmpty()) Text(row.tags, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.management?.let { label -> TextButton(onClick = { onManage(NoteId(row.id)) }, enabled = enabled) { Text(label) } }
        }
    }
}

@Composable private fun ChoiceMenu(label: String, choices: List<String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label) }
        // 长选项使用独立限高列表，避免 DropdownMenu 一次组合全库标签／笔记本。
        if (choices.size > 20) {
            if (expanded) AlertDialog(
                onDismissRequest = { expanded = false }, title = { Text(label) },
                text = {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("note-filter-choices")) {
                        items(choices, key = { it }, contentType = { "choice" }) { value ->
                            TextButton(onClick = { expanded = false; onChoose(value) }, modifier = Modifier.fillMaxWidth()) { Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } },
            )
        } else DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { expanded = false; onChoose(value) }) }
        }
    }
}
