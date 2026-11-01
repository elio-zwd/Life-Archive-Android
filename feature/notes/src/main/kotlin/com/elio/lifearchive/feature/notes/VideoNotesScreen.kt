package com.elio.lifearchive.feature.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.core.preferences.MAX_VIDEO_PROMPT_LENGTH
import com.elio.lifearchive.core.preferences.VideoPromptType
import com.elio.lifearchive.domain.notes.NoteId
import com.elio.lifearchive.core.document.DocumentAnalyzer

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip
    return if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
}

@Composable
fun VideoNotesRoute(model: VideoNotesViewModel, active: Boolean = true, onBack: () -> Unit, onCreated: (NoteId) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(active) {
        // 首页点击明确授权本次读取；重建或从阅读返回时不再次读取剪贴板。
        if (active && model.captureOnly) model.initialPaste { readClipboard(context) }
    }
    val leave = { model.leave(onBack) }
    BackHandler(enabled = active) { leave() }
    LaunchedEffect(state.createdNote, active) {
        if (active) state.createdNote?.let(onCreated)
    }
    VideoNotesScreen(
        state, model.folder, onBack = leave, onTab = model::tab, onType = model::select, onPrompt = model::prompt,
        onSavePrompt = { model.savePrompt() }, onRestorePrompt = { confirmRestore = true },
        onCopy = {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("视频总结提示词", state.prompt))
                model.message("提示词已复制，可切换到点点使用")
            } catch (e: Exception) { model.error("复制失败：${e.message ?: "请重试"}") }
        },
        onName = model::name, onBody = model::body, onSource = model::source,
        onPaste = {
            try {
                // 只在明确点击后读取文字；不把 URI 强制转换为文本或访问剪贴板内容。
                model.paste(readClipboard(context))
            } catch (e: Exception) { model.error("读取剪贴板失败：${e.message ?: "请手动粘贴"}") }
        },
        onCreate = model::create, onReload = model::loadPrompts,
        captureOnly = model.captureOnly,
    )
    if (state.pendingPaste != null) AlertDialog(
        onDismissRequest = { model.confirmPaste(false) }, title = { Text("替换已有正文？") },
        text = { Text("将重新解析这次复制的内容；手动修改的笔记名和来源保留。") },
        confirmButton = { TextButton(onClick = { model.confirmPaste(true) }) { Text("替换正文") } },
        dismissButton = { TextButton(onClick = { model.confirmPaste(false) }) { Text("取消") } },
    )
    if (state.confirmLeave) AlertDialog(
        onDismissRequest = model::cancelLeave, title = { Text("放弃未保存的输入？") },
        text = { Text("未保存的总结和提示词修改将被放弃，已保存的提示词保留。") },
        confirmButton = { TextButton(onClick = onBack) { Text("放弃并返回") } },
        dismissButton = { TextButton(onClick = model::cancelLeave) { Text("继续编辑") } },
    )
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false }, title = { Text("恢复${state.selectedType.label}默认提示词？") },
        text = { Text("仅恢复这一类，当前修改和已保存的自定义内容将被替换。") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; model.savePrompt(restoreDefault = true) }) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoNotesScreen(
    state: VideoNotesUiState, folder: String?, onBack: () -> Unit, onTab: (Int) -> Unit,
    onType: (VideoPromptType) -> Unit, onPrompt: (String) -> Unit,
    onSavePrompt: () -> Unit, onRestorePrompt: () -> Unit, onCopy: () -> Unit,
    onName: (String) -> Unit, onBody: (String) -> Unit, onSource: (String) -> Unit,
    onPaste: () -> Unit, onCreate: () -> Unit, onReload: () -> Unit,
    captureOnly: Boolean = false,
) {
    val promptScroll = rememberScrollState()
    val captureScroll = rememberScrollState()
    Scaffold(
        modifier = Modifier.testTag("video-notes"),
        topBar = {
            TopAppBar(title = { Text(if (captureOnly) "粘贴预览" else "视频摘记") }, navigationIcon = {
                TextButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "返回" }) { Text("返回") }
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!captureOnly) PrimaryTabRow(selectedTabIndex = state.tab) {
                listOf("复制提示词", "收录总结").forEachIndexed { index, label ->
                    Tab(selected = state.tab == index, enabled = !state.busy, onClick = { onTab(index) }, text = { Text(label) }, modifier = Modifier.testTag("video-tab-$index"))
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("video-error")) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("video-message")) }
            state.warning?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("paste-warning")) }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(if (state.tab == 0) promptScroll else captureScroll).padding(16.dp).testTag("video-form"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.tab == 0) {
                    Text("选择类型，复制到点点总结视频。模板是试用初稿，可以按实际效果修改。", style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(VideoPromptType.entries, key = { it.name }) { type ->
                            FilterChip(selected = state.selectedType == type, enabled = !state.busy, onClick = { onType(type) }, label = { Text(type.label) }, modifier = Modifier.testTag("video-type-${type.name}"))
                        }
                    }
                    if (state.loadingPrompts) CircularProgressIndicator(Modifier.size(24.dp))
                    if (!state.loadingPrompts && !state.promptsLoaded) OutlinedButton(onClick = onReload) { Text("重试读取提示词") }
                    val ready = state.promptsLoaded && !state.busy
                    OutlinedTextField(
                        value = state.prompt, onValueChange = onPrompt, label = { Text("${state.selectedType.label}提示词") },
                        enabled = ready, minLines = 8, maxLines = 14,
                        isError = state.prompt.isBlank() || state.prompt.length > MAX_VIDEO_PROMPT_LENGTH,
                        supportingText = { Text("${state.prompt.length} / $MAX_VIDEO_PROMPT_LENGTH 字符 · 修改后点击保存") },
                        modifier = Modifier.fillMaxWidth().testTag("video-prompt"),
                    )
                    Button(onClick = onCopy, enabled = ready && state.prompt.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("video-copy")) { Text("复制当前提示词") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSavePrompt, enabled = ready && state.prompt.isNotBlank() && state.prompt.length <= MAX_VIDEO_PROMPT_LENGTH, modifier = Modifier.weight(1f).testTag("video-save-prompt")) { Text(if (state.savingPrompt) "正在保存…" else "保存修改") }
                        TextButton(onClick = onRestorePrompt, enabled = ready, modifier = Modifier.weight(1f).testTag("video-restore-prompt")) { Text("恢复默认") }
                    }
                } else {
                    Text("保存到：${folder ?: "根目录"}。已自动识别名称和标签，确认内容后保存；普通文本也可收录。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(state.name, onName, label = { Text("笔记名（文件名，不含 .md）") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("video-name"))
                    val tags = remember(state.body) { runCatching { DocumentAnalyzer.analyze(state.body).tags }.getOrDefault(emptyList()) }
                    Text(if (tags.isEmpty()) "标签：无（可直接保存）" else "标签：${tags.joinToString("、")}", modifier = Modifier.testTag("paste-tags"))
                    OutlinedButton(onClick = onPaste, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("video-paste")) { Text(if (captureOnly) "重新粘贴" else "粘贴总结") }
                    OutlinedTextField(state.body, onBody, label = { Text("Markdown 内容（含 YAML，可修改）") }, minLines = 6, maxLines = 12, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("video-body"))
                    if (!captureOnly) OutlinedTextField(state.source, onSource, label = { Text("来源（可选）") }, supportingText = { Text("可粘贴原始分享文字或链接，仅追加到正文末尾。") }, minLines = 2, maxLines = 4, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("video-source"))
                    Button(onClick = onCreate, enabled = !state.busy && state.body.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("video-save-note")) { Text(if (state.savingNote) "正在保存…" else "保存") }
                }
            }
        }
    }
}
