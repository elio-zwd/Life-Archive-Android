package com.elio.lifearchive.feature.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun EditorInsertionDialog(state: EditorUiState, actions: EditorToolActions) {
    val prompt = state.insertion ?: return
    val title = when (prompt.kind) { EditorInsertKind.EXTERNAL_LINK -> "插入链接"; EditorInsertKind.WIKI_LINK -> "插入笔记链接"; EditorInsertKind.CODE_BLOCK -> "插入代码块"; EditorInsertKind.CALLOUT -> "插入 Callout" }
    AlertDialog(onDismissRequest = actions.dismiss, title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (prompt.kind) {
                    EditorInsertKind.EXTERNAL_LINK -> {
                        OutlinedTextField(prompt.label, { text -> actions.edit { it.copy(label = text) } }, label = { Text("显示文字") }, modifier = Modifier.testTag("insert-label"), maxLines = 2)
                        OutlinedTextField(prompt.target, { text -> actions.edit { it.copy(target = text) } }, label = { Text("链接地址") }, modifier = Modifier.testTag("insert-target"), maxLines = 2)
                    }
                    EditorInsertKind.CODE_BLOCK -> OutlinedTextField(prompt.parameter, { text -> actions.edit { it.copy(parameter = text) } }, label = { Text("语言（留空为 text）") }, singleLine = true, modifier = Modifier.testTag("insert-language"))
                    EditorInsertKind.CALLOUT -> listOf("NOTE", "TIP", "WARNING", "DANGER", "QUESTION", "EXAMPLE").chunked(2).forEach { pair ->
                        Row { pair.forEach { type -> FilterChip(selected = prompt.parameter == type, onClick = { actions.edit { it.copy(parameter = type) } }, label = { Text(type) }, modifier = Modifier.padding(end = 8.dp)) } }
                    }
                    EditorInsertKind.WIKI_LINK -> {
                        OutlinedTextField(prompt.query, actions.search, label = { Text("搜索笔记") }, singleLine = true, modifier = Modifier.testTag("insert-note-query"))
                        OutlinedTextField(prompt.label, { text -> actions.edit { it.copy(label = text) } }, label = { Text("显示别名（可选）") }, maxLines = 2)
                        Row { Checkbox(prompt.markdown, { checked -> actions.edit { it.copy(markdown = checked) } }); Text("使用 Markdown 链接", modifier = Modifier.padding(top = 12.dp)) }
                        if (state.linkLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!state.linkLoading && state.linkChoices.isEmpty() && state.insertionError == null) Text("没有匹配的笔记")
                        state.linkChoices.take(50).forEach { choice ->
                            FilterChip(selected = prompt.selected?.id == choice.id, onClick = { actions.edit { it.copy(selected = choice) } }, label = { Column { Text(choice.path); choice.title?.let { Text(it, style = MaterialTheme.typography.labelSmall) } } }, modifier = Modifier.fillMaxWidth())
                        }
                        if (state.linkChoices.size > 50) Text("请缩小搜索范围以查看其他笔记", style = MaterialTheme.typography.labelSmall)
                    }
                }
                state.insertionError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(onClick = actions.reanchor) { Text("重新定位") }
                        if (prompt.kind == EditorInsertKind.WIKI_LINK) TextButton(onClick = { actions.search(prompt.query) }) { Text("重试查询") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.commandRunning && (prompt.kind != EditorInsertKind.WIKI_LINK || prompt.selected != null), onClick = {
                when (prompt.kind) {
                    EditorInsertKind.EXTERNAL_LINK -> actions.external(prompt.label, prompt.target)
                    EditorInsertKind.WIKI_LINK -> prompt.selected?.let { actions.note(it, prompt.label, prompt.markdown) }
                    EditorInsertKind.CODE_BLOCK, EditorInsertKind.CALLOUT -> actions.template(prompt.parameter)
                }
            }) { Text("插入", modifier = Modifier.testTag("insert-confirm")) }
        },
        dismissButton = { TextButton(onClick = actions.dismiss) { Text("取消") } },
    )
}
