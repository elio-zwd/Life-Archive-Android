package com.elio.lifearchive.feature.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.core.document.DocumentAnalyzer
import com.elio.lifearchive.core.designsystem.CoverArtwork
import com.elio.lifearchive.domain.notes.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private data class HomeColors(
    val background: Color, val surface: Color, val ink: Color, val muted: Color,
    val line: Color, val chip: Color, val selected: Color, val blue: Color, val fab: Color,
)

@Composable
private fun homeColors(): HomeColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) HomeColors(
        Color(0xFF25292F), Color(0xFF303740), Color(0xFFF0F3F9), Color(0xFFB3BFCD),
        Color(0xFF424A56), Color(0xFF39424D), Color(0xFF253D5D), Color(0xFF8AB7FF), Color(0xFF287FF7),
    ) else HomeColors(
        Color(0xFFFCFDFF), Color.White, Color(0xFF101F37), Color(0xFF5F6E82),
        Color(0xFFE9EDF3), Color(0xFFF1F4F8), Color(0xFFEAF2FF), Color(0xFF145ED7), Color(0xFF1766F5),
    )

@Composable
fun NotesScreen(
    state: NotesUiState, onOpen: (NoteId) -> Unit, onCreate: (String, (NoteId) -> Unit) -> Unit,
    onCreateSample: () -> Unit, onTheme: () -> Unit, themeLabel: String, onClearError: () -> Unit,
    sampleBusy: Boolean = false, message: String? = null,
    onCreated: (NoteId) -> Unit = onOpen,
    onQuery: (NotesQuery) -> Unit = {}, onSettings: () -> Unit = {}, onBackup: () -> Unit = {}, onQuick: () -> Unit = {},
    onManage: (LibraryNote) -> Unit = {}, onFolder: () -> Unit = {}, onRebuild: () -> Unit = {}, onFolderManage: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    loadThumbnail: suspend (NoteId, String, LinkSyntax) -> ImageBitmap? = { _, _, _ -> null },
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var tagPicker by remember { mutableStateOf(false) }
    val colors = homeColors()
    val presentation = remember(state.notes, state.listing) { noteListPresentation(state.notes, state.listing) }
    // 行动作只读取最新回调；详情在一次列表快照中按 ID 关联。
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnManage by rememberUpdatedState(onManage)
    val open: (NoteId) -> Unit = remember { { id -> currentOnOpen(id) } }
    val manage: (NoteId) -> Unit = remember(presentation) { { id -> presentation.details[id]?.let(currentOnManage); Unit } }
    val folders = remember(state.listing?.folders) { state.listing?.folders.orEmpty().filter { it.isNotBlank() }.distinct() }
    val tags = remember(state.listing?.tags) { (listOf("全部标签") + state.listing?.tags.orEmpty()).distinct() }
    val canCountFolders = state.query.folder == null && state.query.tag == null && state.query.text.isBlank() && !state.query.trash
    val folderCounts = remember(presentation, canCountFolders) {
        if (!canCountFolders) emptyMap<String, Int>() else buildMap<String, Int> {
            presentation.rows.forEach { row ->
                var folder = row.folder
                while (folder.isNotEmpty()) {
                    put(folder, (get(folder) ?: 0) + 1)
                    folder = folder.substringBeforeLast('/', "")
                }
            }
        }
    }
    val visibleKeys by remember(listState) {
        derivedStateOf(structuralEqualityPolicy()) { listState.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.key } }
    }
    val clearFilters = { onQuery(state.query.copy(text = "", folder = null, tag = null)) }
    val newNote = { naming = true; onClearError() }
    Scaffold(
        containerColor = colors.background,
        topBar = {
            Column(Modifier.fillMaxWidth().background(colors.background).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Life Archive", Modifier.weight(1f), color = colors.ink, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    HomeIconButton("本地备份", "cloud", colors.ink, onBackup)
                    Box {
                        HomeIconButton("更多操作", "more", colors.ink, { more = true }, Modifier.testTag("home-more"))
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("快速记录") }, enabled = !state.query.trash && !state.creating, onClick = { more = false; onQuick() })
                            DropdownMenuItem(text = { Text("新建笔记本") }, onClick = { more = false; onFolder() })
                            if (state.query.folder != null) DropdownMenuItem(text = { Text("移动笔记本") }, onClick = { more = false; onFolderManage() })
                            DropdownMenuItem(text = { Column { Text("标签筛选"); Text(state.query.tag ?: "全部标签", style = MaterialTheme.typography.bodySmall) } }, onClick = { more = false; tagPicker = true })
                            DropdownMenuItem(text = { Text(if (state.query.trash) "返回笔记" else "回收站") }, onClick = { more = false; onQuery(state.query.copy(trash = !state.query.trash)) })
                            DropdownMenuItem(text = { Text("主题：$themeLabel") }, onClick = { more = false; onTheme() })
                            DropdownMenuItem(text = { Text(if (sampleBusy) "正在创建验收样例…" else "创建验收样例") }, enabled = !sampleBusy, onClick = { more = false; onCreateSample() })
                        }
                    }
                }
                SearchBox(state.query.text, colors, { onQuery(state.query.copy(text = it)) })
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f).testTag("folder-filters"),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item(key = "all-folders", contentType = "folder") {
                            FolderChip("全部笔记", if (canCountFolders) state.notes.size else null, state.query.folder == null, colors) {
                                onQuery(state.query.copy(folder = null))
                            }
                        }
                        items(folders, key = { "folder:$it" }, contentType = { "folder" }) { folder ->
                            FolderChip(folder, folderCounts[folder]?.takeIf { canCountFolders }, state.query.folder == folder, colors) {
                                onQuery(state.query.copy(folder = folder))
                            }
                        }
                    }
                    Box {
                        HomeIconButton("管理笔记本", "plus", colors.muted, { folderMenu = true })
                        DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                            DropdownMenuItem(text = { Text("新建笔记本") }, onClick = { folderMenu = false; onFolder() })
                            if (state.query.folder != null) DropdownMenuItem(text = { Text("移动笔记本") }, onClick = { folderMenu = false; onFolderManage() })
                        }
                    }
                }
                HorizontalDivider(color = colors.line)
            }
        },
        bottomBar = { HomeBottomBar(colors, onSettings) },
        floatingActionButton = {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(colors.fab)
                    .clickable(enabled = !state.creating, onClick = newNote)
                    .semantics { contentDescription = "新建笔记" }.testTag("new-note"),
                contentAlignment = Alignment.Center,
            ) { HomeIcon("pencil", Color.White, Modifier.size(24.dp)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterSummary(state, colors, clearFilters, onQuery)
            LazyColumn(
                Modifier.fillMaxSize().testTag("notes-list"), state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp),
            ) {
                if (state.loading) item(key = "loading", contentType = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.updating && !state.loading) item(key = "updating", contentType = "status") { Text("正在更新列表…", Modifier.padding(vertical = 8.dp), color = colors.muted, style = MaterialTheme.typography.labelSmall) }
                state.progress?.let { progress -> item(key = "progress", contentType = "progress") {
                    Text("${progress.operation} ${progress.completed}/${progress.total}", color = colors.muted)
                    LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                } }
                state.listing?.pendingIndex?.takeIf { it > 0 }?.let { pending -> item(key = "pending", contentType = "status") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$pending 篇索引待更新", Modifier.weight(1f), color = colors.muted)
                        TextButton(onClick = onRebuild) { Text("重试索引") }
                    }
                } }
                if (state.error != null && !naming) item(key = "error", contentType = "status") { Text(state.error, color = MaterialTheme.colorScheme.error) }
                if (state.error != null && !naming) item(key = "retry", contentType = "action") { TextButton(onClick = onRebuild) { Text("重建索引并重试") } }
                if (message != null) item(key = "message", contentType = "status") { Text(message, color = colors.blue, style = MaterialTheme.typography.bodySmall) }
                if (!state.loading && !state.updating && state.notes.isEmpty()) item(key = "empty", contentType = "empty") {
                    val filtered = state.query.text.isNotBlank() || state.query.folder != null || state.query.tag != null
                    EmptyNotes(colors, filtered, state.query.trash, if (filtered) clearFilters else newNote)
                }
                items(presentation.rows, key = { "note:${it.id}" }, contentType = { "note" }) { row ->
                    NoteRow(
                        row = row, colors = colors, enabled = !state.updating,
                        visible = "note:${row.id}" in visibleKeys, thumbnailEpoch = state.thumbnailEpoch,
                        loadThumbnail = loadThumbnail, onOpen = open, onManage = manage,
                    )
                }
            }
        }
    }
    if (tagPicker) FilterPicker("筛选标签", tags, { tagPicker = false }) { selected ->
        tagPicker = false
        onQuery(state.query.copy(tag = selected.takeUnless { it == "全部标签" }))
    }
    if (naming) AlertDialog(
        onDismissRequest = { if (!state.creating) naming = false },
        title = { Text("新建笔记") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it; onClearError() }, label = { Text("笔记名（不含 .md）") }, singleLine = true, enabled = !state.creating, isError = state.error != null, modifier = Modifier.testTag("note-name"))
                if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Text("文件名与正文标题、YAML title 各自独立。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(enabled = !state.creating, onClick = { onCreate(name) { id -> naming = false; name = ""; onCreated(id) } }) { Text(if (state.creating) "创建中…" else "创建") } },
        dismissButton = { TextButton(enabled = !state.creating, onClick = { naming = false; name = ""; onClearError() }) { Text("取消") } },
    )
}

@Composable
private fun SearchBox(value: String, colors: HomeColors, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp)
            .height(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.chip).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeIcon("search", colors.muted, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, cursorBrush = SolidColor(colors.blue),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink, fontSize = 14.sp),
            modifier = Modifier.weight(1f).semantics { contentDescription = "搜索文件名、标题与正文" }.testTag("note-search"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("搜索文件名、标题与正文", color = colors.muted, fontSize = 14.sp, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) HomeIconButton("清空搜索", "close", colors.muted, { onChange("") }, Modifier.testTag("clear-search"))
        else Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun FolderChip(label: String, count: Int?, selected: Boolean, colors: HomeColors, onClick: () -> Unit) {
    val text = if (count == null) label else "$label $count"
    Box(
        Modifier.height(36.dp).widthIn(max = 190.dp).clip(CircleShape)
            .background(if (selected) colors.selected else colors.chip)
            .clickable(onClick = onClick).padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) colors.blue else colors.muted, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FilterSummary(state: NotesUiState, colors: HomeColors, clearFilters: () -> Unit, onQuery: (NotesQuery) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    val sortLabels = listOf("最近修改", "较早修改", "最近创建", "较早创建", "名称")
    val filtered = state.query.text.isNotBlank() || state.query.folder != null || state.query.tag != null
    val summary = buildAnnotatedString {
        append("${state.notes.size} 篇")
        if (state.query.trash) append(" · 回收站")
        state.query.folder?.let { append(" · $it") }
        state.query.tag?.let { append(" · #$it") }
        if (state.query.text.isNotBlank()) append(" · 搜索结果")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 20.dp, end = 20.dp).testTag("filter-summary"),
        verticalAlignment = Alignment.Top,
    ) {
        Text(summary, Modifier.weight(1f).padding(top = 13.dp, bottom = 9.dp), color = colors.ink, fontSize = 12.sp, lineHeight = 20.sp)
        Box {
            Row(
                Modifier.heightIn(min = 44.dp).clickable { sortMenu = true }.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeIcon("sort", colors.muted, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(sortLabels[state.query.sort.ordinal], color = colors.muted, fontSize = 12.sp)
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                sortLabels.forEachIndexed { index, label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { sortMenu = false; onQuery(state.query.copy(sort = NoteSort.entries[index])) })
                }
            }
        }
        if (filtered) Text("清除", Modifier.clickable(onClick = clearFilters).padding(start = 10.dp, top = 13.dp, bottom = 13.dp), color = colors.blue, fontSize = 12.sp)
    }
}

@Composable
private fun FilterPicker(title: String, choices: List<String>, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("note-filter-choices")) {
                items(choices, key = { it }, contentType = { "choice" }) { value ->
                    TextButton(onClick = { onChoose(value) }, modifier = Modifier.fillMaxWidth()) {
                        Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EmptyNotes(colors: HomeColors, filtered: Boolean, trash: Boolean, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HomeIcon(if (filtered) "search" else "notes", colors.muted, Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(if (filtered) "没有找到笔记" else if (trash) "回收站为空" else "从一篇笔记开始", color = colors.ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(if (filtered) "试试其他关键词，或清除笔记本与标签筛选。" else if (trash) "删除的笔记会暂时保存在这里。" else "给它一个名字，再慢慢写下想留下的事。", color = colors.muted, fontSize = 13.sp)
        if (filtered || !trash) TextButton(onClick = onAction, modifier = Modifier.testTag(if (filtered) "empty-clear-filters" else "empty-new-note")) {
            Text(if (filtered) "清除筛选" else "新建笔记")
        }
    }
}

@Composable
private fun NoteRow(
    row: NoteRowUi, colors: HomeColors, enabled: Boolean, visible: Boolean, thumbnailEpoch: Long,
    loadThumbnail: suspend (NoteId, String, LinkSyntax) -> ImageBitmap?,
    onOpen: (NoteId) -> Unit, onManage: (NoteId) -> Unit,
) {
    val currentLoader by rememberUpdatedState(loadThumbnail)
    var image by remember(row.id, row.cover) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(row.id, row.cover, row.modified, thumbnailEpoch, visible) {
        image = null
        val attachment = row.cover as? NoteCover.Attachment
        if (visible && attachment != null) image = currentLoader(NoteId(row.id), "/" + DocumentAnalyzer.encode(attachment.path.value), LinkSyntax.WIKI)
    }
    val date = remember(row.modified) { row.modified?.let { SimpleDateFormat("yyyy/M/d", Locale.CHINA).format(Date(it)) } }
    Column(Modifier.fillMaxWidth().testTag("note:${row.id}").clickable(enabled = enabled) { onOpen(NoteId(row.id)) }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArtwork(preset = (row.cover as? NoteCover.Preset)?.preset?.key ?: "mountain", image = image, enabled = visible,
                modifier = Modifier.size(width = 60.dp, height = 66.dp).clip(RoundedCornerShape(8.dp)).testTag("note-thumbnail:${row.id}"))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    if (row.pinned) HomeIcon("pin", colors.blue, Modifier.padding(top = 4.dp, end = 5.dp).size(13.dp).testTag("note-pin:${row.id}"))
                    Text(row.name, color = colors.ink, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text(row.excerpt.ifBlank { "点击查看笔记。" }, color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.subtitle == "正文已保存 · 索引待更新") Text(row.subtitle, color = colors.muted, fontSize = 11.sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (date != null) Text(date, Modifier.testTag("note-date:${row.id}"), color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
                    Row(Modifier.widthIn(max = 85.dp), verticalAlignment = Alignment.CenterVertically) {
                        HomeIcon("folder", colors.muted, Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(row.folder.ifBlank { "未分类" }, color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (row.tags.isNotEmpty()) Text(row.tags, Modifier.weight(1f), color = colors.blue, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.management != null) HomeIconButton(row.management, "more", colors.muted, { onManage(NoteId(row.id)) },
                Modifier.testTag("note-manage:${row.id}"), enabled = enabled)
        }
        HorizontalDivider(color = colors.line)
    }
}

@Composable
private fun HomeBottomBar(colors: HomeColors, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        HorizontalDivider(color = colors.line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            HomeTab("笔记", "notes", colors.blue, Modifier.weight(1f)) {}
            HomeTab("设置", "settings", colors.muted, Modifier.weight(1f), onSettings)
        }
    }
}

@Composable
private fun HomeTab(label: String, icon: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        HomeIcon(icon, color, Modifier.size(23.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HomeIconButton(label: String, icon: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { HomeIcon(icon, if (enabled) color else color.copy(alpha = 0.4f), Modifier.size(20.dp)) }
}

@Composable
private fun HomeIcon(name: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 1.9f * u, cap = StrokeCap.Round)
        fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = stroke)
        when (name) {
            "search" -> { drawCircle(color, 7f * u, Offset(10f * u, 10f * u), style = stroke); line(15.5f, 15.5f, 21f, 21f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "more" -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.25f * u, Offset(12f * u, it * u)) }
            "plus" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "sort" -> { line(3f, 6f, 21f, 6f); line(6f, 12f, 18f, 12f); line(9f, 18f, 15f, 18f) }
            "folder" -> path { moveTo(3f*u, 6f*u); lineTo(9f*u, 6f*u); lineTo(11f*u, 8f*u); lineTo(21f*u, 8f*u); lineTo(21f*u, 19f*u); lineTo(3f*u, 19f*u); close() }
            "pin" -> path { moveTo(9f*u, 3f*u); lineTo(18f*u, 7f*u); lineTo(15f*u, 10f*u); lineTo(16f*u, 15f*u); lineTo(12f*u, 14f*u); lineTo(7f*u, 21f*u); lineTo(8f*u, 13f*u); lineTo(5f*u, 10f*u); lineTo(9f*u, 9f*u); close() }
            "pencil" -> path { moveTo(4f*u, 20f*u); lineTo(5f*u, 15f*u); lineTo(16f*u, 4f*u); lineTo(20f*u, 8f*u); lineTo(9f*u, 19f*u); close(); moveTo(14f*u, 6f*u); lineTo(18f*u, 10f*u) }
            "cloud" -> path { moveTo(7f*u, 19f*u); cubicTo(2f*u, 19f*u, 2f*u, 11f*u, 7f*u, 11f*u); cubicTo(8f*u, 4f*u, 17f*u, 4f*u, 19f*u, 10f*u); cubicTo(24f*u, 11f*u, 23f*u, 19f*u, 19f*u, 19f*u); close() }
            "notes" -> {
                drawRoundRect(color, Offset(4f*u, 2f*u), Size(16f*u, 20f*u), CornerRadius(2f*u), style = stroke)
                line(8f, 7f, 16f, 7f); line(8f, 11f, 16f, 11f); line(8f, 15f, 14f, 15f)
            }
            "settings" -> {
                drawCircle(color, 3.2f*u, Offset(12f*u, 12f*u), style = stroke)
                drawCircle(color, 7.5f*u, Offset(12f*u, 12f*u), style = stroke)
                repeat(8) { index ->
                    val angle = index * Math.PI / 4
                    line((12 + 7.5 * cos(angle)).toFloat(), (12 + 7.5 * sin(angle)).toFloat(), (12 + 10 * cos(angle)).toFloat(), (12 + 10 * sin(angle)).toFloat())
                }
            }
        }
    }
}
