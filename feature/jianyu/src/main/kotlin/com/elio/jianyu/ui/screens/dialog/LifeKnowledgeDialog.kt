package com.elio.jianyu.ui.screens.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elio.jianyu.domain.LifeKnowledge
import com.elio.jianyu.domain.LifeKnowledgeHit
import com.elio.jianyu.domain.LifeKnowledgeQuery
import com.elio.jianyu.domain.LifeKnowledgeResult
import com.elio.jianyu.domain.LifeNoteKind
import com.elio.jianyu.domain.OpenLifeNote
import com.elio.jianyu.ui.lifecycle.sessionCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun LifeKnowledgeDialog(knowledge: LifeKnowledge, onDismiss: () -> Unit, onOpened: () -> Unit) {
    var query by remember { mutableStateOf(LifeKnowledgeQuery()) }
    var result by remember { mutableStateOf<LifeKnowledgeResult?>(null) }
    var completedQuery by remember { mutableStateOf<LifeKnowledgeQuery?>(null) }
    var filters by remember { mutableStateOf<LifeKnowledgeResult.Found?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<LifeKnowledgeHit?>(null) }
    var opening by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    val scope = sessionCoroutineScope()

    val requested = query
    LaunchedEffect(knowledge, requested, retry) {
        if (requested.text.isNotBlank()) delay(180)
        val latest = knowledge.search(requested)
        result = latest
        completedQuery = requested
        if (latest is LifeKnowledgeResult.Found) filters = latest
    }

    AlertDialog(
        onDismissRequest = { if (!opening) onDismiss() },
        title = { Text("Life 笔记") },
        text = {
            val selected = preview
            if (selected != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.title, style = MaterialTheme.typography.titleMedium)
                    Text(selected.path, style = MaterialTheme.typography.labelSmall)
                    Text(selected.excerpt.ifBlank { "没有可预览的正文片段" })
                    openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query.text,
                        onValueChange = { query = query.copy(text = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索标题或正文") },
                        singleLine = true,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        KnowledgeFilter("主题", query.topic, filters?.topics.orEmpty(), { it }) { query = query.copy(topic = it) }
                        KnowledgeFilter("类型", query.kind, LifeNoteKind.entries, {
                            when (it) {
                                LifeNoteKind.PERSONAL -> "个人笔记"
                                LifeNoteKind.VIDEO -> "视频摘记"
                            }
                        }) { query = query.copy(kind = it) }
                        KnowledgeFilter("标签", query.tag, filters?.tags.orEmpty(), { it }) { query = query.copy(tag = it) }
                    }
                    when (val listing = result.takeIf { completedQuery == query }) {
                        null -> Text("正在查找…")
                        LifeKnowledgeResult.Unavailable -> {
                            Text("暂时无法读取 Life 笔记", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { result = null; retry++ }) { Text("重试") }
                        }
                        is LifeKnowledgeResult.Found -> {
                            if (listing.pendingIndex > 0) {
                                Text("有 ${listing.pendingIndex} 篇笔记的索引待更新", style = MaterialTheme.typography.bodySmall)
                            }
                            if (listing.hits.isEmpty()) Text("没有匹配的笔记")
                            if (listing.total > listing.hits.size) {
                                Text("显示 ${listing.total} 条结果中的前 ${listing.hits.size} 条", style = MaterialTheme.typography.bodySmall)
                            }
                            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                items(listing.hits, key = { it.noteId }) { hit ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable(role = Role.Button) { preview = hit }
                                            .padding(vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(hit.title, style = MaterialTheme.typography.titleSmall)
                                        Text(hit.path, style = MaterialTheme.typography.labelSmall)
                                        if (hit.excerpt.isNotBlank()) {
                                            Text(hit.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = if (preview == null) null else {
            { TextButton(onClick = { preview = null; openError = null }, enabled = !opening) { Text("返回结果") } }
        },
        confirmButton = {
            val selected = preview
            if (selected == null) TextButton(onClick = onDismiss) { Text("关闭") }
            else TextButton(enabled = !opening, onClick = open@{
                if (opening) return@open
                opening = true
                scope.launch {
                    try {
                        when (knowledge.open(selected)) {
                            OpenLifeNote.OPENED -> onOpened()
                            OpenLifeNote.MISSING -> openError = "这篇笔记已移入回收站或不存在"
                            OpenLifeNote.UNAVAILABLE -> openError = "暂时无法打开原文，请重试"
                        }
                    } finally {
                        opening = false
                    }
                }
            }) { Text("打开 Life 原文") }
        },
    )
}

@Composable
private fun <T : Any> KnowledgeFilter(
    label: String,
    selected: T?,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            enabled = options.isNotEmpty() || selected != null,
            onClick = { expanded = true },
            label = { Text(selected?.let(display) ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部$label") }, onClick = { expanded = false; onSelect(null) })
            options.forEach { option ->
                DropdownMenuItem(text = { Text(display(option)) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}
