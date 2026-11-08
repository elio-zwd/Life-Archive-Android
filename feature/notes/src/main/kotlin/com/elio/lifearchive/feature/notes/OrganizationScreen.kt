package com.elio.lifearchive.feature.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.domain.notes.*

@Composable
fun OrganizationRoute(model: OrganizationViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = state.busy) { }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(topBar = { Column {
        Row(Modifier.fillMaxWidth().statusBarsPadding(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, enabled = !state.busy) { Text("返回") }
            Text("分类与整理", Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = model::refresh, enabled = !state.busy) { Text("刷新") }
        }
        Row { TextButton(onClick = { tab = 0 }) { Text("笔记整理") }; TextButton(onClick = { tab = 1 }) { Text("分类管理") } }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("organization-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.result?.let { item { Text(it, modifier = Modifier.testTag("organization-result")) } }
            if (tab == 0) {
                item {
                    Text("先选择笔记，再设置本批次归属。建议只取自目录和已有标签，不分析正文；多候选时请自行选择。未确定主题或类型会进入待整理。")
                    TextButton(onClick = model::selectAll, enabled = !state.busy) { Text("全选 ${state.suggestions.size} 篇") }
                    OutlinedTextField(state.topic, { value -> model.edit { it.copy(topic = value) } }, label = { Text("主主题（空白为待整理）") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("organization-topic"))
                    LazyRow { items(state.topics) { topic -> TextButton(onClick = { model.edit { it.copy(topic = topic) } }, enabled = !state.busy) { Text(topic) } } }
                    Row { FilterChip(state.kind == null, { model.edit { it.copy(kind = null) } }, label = { Text("类型待定") }, enabled = !state.busy)
                        NoteKind.entries.forEach { kind -> FilterChip(state.kind == kind, { model.edit { it.copy(kind = kind) } }, label = { Text(kind.folder) }, enabled = !state.busy, modifier = Modifier.testTag("organization-kind-${kind.name}")) }
                    }
                    Row { Checkbox(state.replaceTags, { value -> model.edit { it.copy(replaceTags = value) } }, enabled = !state.busy); Text("统一设置属性标签（不勾选则保留）", Modifier.padding(top = 12.dp)) }
                    if (state.replaceTags) OutlinedTextField(state.tags, { value -> model.edit { it.copy(tags = value) } }, label = { Text("最多三级，多个用逗号分隔") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                    Row { Checkbox(state.confirmTimes, { value -> model.edit { it.copy(confirmTimes = value) } }, enabled = !state.busy); Text("我已核实本批次的内容时间", Modifier.padding(top = 12.dp)) }
                    if (state.confirmTimes) {
                        Text("只填写有可靠依据的时间。支持日期或带时区时间；空白表示保留。App 收录／活动时间不是原始写作时间。")
                        OutlinedTextField(state.created, { value -> model.edit { it.copy(created = value) } }, label = { Text("首次写录：2026-11-09 或带时区时间") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                        OutlinedTextField(state.updated, { value -> model.edit { it.copy(updated = value) } }, label = { Text("内容修改：2026-11-09T12:00:00+08:00") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                    }
                    Button(onClick = model::preview, enabled = !state.busy && state.selected.isNotEmpty(), modifier = Modifier.testTag("organization-preview")) { Text("预览 ${state.selected.size} 篇") }
                }
                state.preview?.let { preview ->
                    item { Text("预览：确认前尚未修改文件", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = model::apply, enabled = !state.busy, modifier = Modifier.testTag("organization-apply")) { Text("确认整理") }
                    }
                    items(preview.items, key = { "preview:${it.change.id.value}" }) { item ->
                        Text("${item.from}\n→ ${item.to}\n标签：${item.beforeTags.joinToString()} → ${item.afterTags.joinToString()}\n涉及链接：${item.affectedLinks.joinToString().ifEmpty { "无" }}${item.change.times?.let { "\n确认时间：$it" }.orEmpty()}${item.issue?.let { "\n将跳过：$it" }.orEmpty()}")
                        HorizontalDivider()
                    }
                }
                items(state.suggestions, key = { it.note.summary.id.value }) { suggestion ->
                    val note = suggestion.note
                    Row {
                        Checkbox(note.summary.id in state.selected, { model.select(note.summary.id) }, enabled = !state.busy)
                        Column(Modifier.weight(1f)) {
                            Text(note.summary.path.value)
                            Text("候选：${suggestion.topics.joinToString().ifEmpty { "待整理" }} · ${suggestion.placement.kind?.folder ?: "类型待定"}", style = MaterialTheme.typography.bodySmall)
                            Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                            Text("首次写录：${note.contentTimes.created ?: "未知"}；内容修改：${if (note.timeUncertain) "待确认" else note.contentTimes.updated ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                            Text("App 内部收录／活动候选：${java.time.Instant.ofEpochMilli(note.created)} / ${java.time.Instant.ofEpochMilli(note.modified)}（不自动回填）", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { model.suggest(note.summary.id) }, enabled = !state.busy) { Text("单独整理／查看建议") }
                        }
                    }
                    HorizontalDivider()
                }
            } else {
                item {
                    Text("主题和标签改名、合并都先预览。新名称已经存在时执行合并；路径冲突会拒绝，不覆盖笔记。")
                    Row { FilterChip(state.taxonomyTopic, { model.edit { it.copy(taxonomyTopic = true) } }, label = { Text("主题") }); FilterChip(!state.taxonomyTopic, { model.edit { it.copy(taxonomyTopic = false) } }, label = { Text("标签") }) }
                    OutlinedTextField(state.from, { value -> model.edit { it.copy(from = value) } }, label = { Text("原名称／标签路径") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                    OutlinedTextField(state.to, { value -> model.edit { it.copy(to = value) } }, label = { Text("新名称／已有名称") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                    TextButton(onClick = model::addTopic, enabled = !state.busy && state.taxonomyTopic && state.to.isNotBlank()) { Text("按新名称新增主题") }
                    Button(onClick = model::previewTaxonomy, enabled = !state.busy && state.from.isNotBlank() && state.to.isNotBlank()) { Text("预览分类调整") }
                }
                state.taxonomy?.let { preview ->
                    item { Text("${preview.change.from} → ${preview.change.to}\n涉及 ${preview.notes.size} 篇、${preview.folders.size} 个目录")
                        Button(onClick = model::applyTaxonomy, enabled = !state.busy) { Text("确认分类调整") }
                    }
                    items(preview.notes, key = { it.summary.id.value }) { Text(it.summary.path.value + "\n" + it.tags.joinToString()) }
                    items(preview.folders, key = { "folder:$it" }) { Text("目录：$it") }
                }
            }
        }
    }
}
