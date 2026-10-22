package com.elio.lifearchive.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("Life Archive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text("本地笔记", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) } }, actions = { TextButton(onClick = onTheme) { Text(themeLabel) }; TextButton(onClick = onSettings) { Text("设置") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { naming = true; onClearError() }) { Text("＋  新建笔记") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("全部笔记", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text("${state.notes.size} 篇", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (state.listing != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = state.query.text, onValueChange = { onQuery(state.query.copy(text = it)) }, label = { Text("搜索文件名、标题与正文") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceMenu(state.query.folder ?: "全部笔记本", listOf("全部笔记本") + state.listing.folders) { onQuery(state.query.copy(folder = it.takeUnless { it == "全部笔记本" })) }
                        ChoiceMenu(state.query.tag ?: "全部标签", listOf("全部标签") + state.listing.tags) { onQuery(state.query.copy(tag = it.takeUnless { it == "全部标签" })) }
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
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.progress?.let { progress -> item { Text("${progress.operation} ${progress.completed}/${progress.total}"); LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth()) } }
            if (state.error != null && !naming) item { Text(state.error, color = colors.error) }
            if (state.error != null && !naming) item { TextButton(onClick = onRebuild) { Text("重建索引并重试") } }
            if (message != null) item { Text(message, color = colors.primary, style = MaterialTheme.typography.bodySmall) }
            if (!state.loading && state.notes.isEmpty()) item {
                Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text("从一篇笔记开始", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        Text("给它一个名字，再慢慢写下想留下的事。笔记保存在此设备中。", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            items(state.notes, key = { it.id.value }) { note ->
                Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable { onOpen(note.id) }) {
                    Column(Modifier.padding(18.dp)) {
                        Text(note.path.value.substringAfterLast('/').removeSuffix(".md"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(if (note.indexPending) "正文已保存 · 索引待更新" else note.path.value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2)
                        state.listing?.notes?.find { it.summary.id == note.id }?.let { detail ->
                            if (detail.tags.isNotEmpty()) Text(detail.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onManage(detail) }) { Text(if (detail.deleted != null) "恢复／永久删除" else if (detail.pinned) "已置顶 · 管理" else "管理") }
                        }
                    }
                }
            }
            item {
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

@Composable private fun ChoiceMenu(label: String, choices: List<String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { TextButton(onClick = { expanded = true }) { Text(label) }; DropdownMenu(expanded, { expanded = false }) { choices.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { expanded = false; onChoose(value) }) } } }
}
