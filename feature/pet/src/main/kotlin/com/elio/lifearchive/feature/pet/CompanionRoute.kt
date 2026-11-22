package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elio.jianyu.network.*
import com.elio.lifearchive.domain.pet.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CompanionRoute(
    repository: CompanionRepository,
    ai: AiServices,
    onBottomBarMeasured: (Int) -> Unit,
    onOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onInAppVisibility: (Boolean) -> Unit = {},
) {
    val model: CompanionViewModel = viewModel(factory = remember(repository) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CompanionViewModel(repository) as T
    } })
    val state by model.state.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editMemory by remember { mutableStateOf<CompanionMemory?>(null) }
    var addMemory by rememberSaveable { mutableStateOf(false) }
    var memorySource by rememberSaveable { mutableStateOf<String?>(null) }
    var memoryText by rememberSaveable { mutableStateOf("") }
    var deleteMemory by remember { mutableStateOf<CompanionMemory?>(null) }
    var showAi by rememberSaveable { mutableStateOf(false) }
    var editProfile by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(state.profile.showInApp) { onInAppVisibility(state.profile.showInApp) }
    LaunchedEffect(state.messages.lastOrNull()?.id, state.generating) {
        if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex)
    }
    Scaffold(Modifier.fillMaxSize().testTag("pet-root"), bottomBar = {
        NavigationBar(Modifier.onSizeChanged { onBottomBarMeasured(it.height) }) {
            listOf("陪伴", "记忆", "设置").forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Text(listOf("◉", "♡", "☷")[index]) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).statusBarsPadding().fillMaxSize().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text(state.profile.name, style = MaterialTheme.typography.headlineSmall)
                    Text("日常里的陪伴", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { showAi = true }) { Text("公共 AI") }
            }
            if (error != null) {
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error.orEmpty(), Modifier.weight(1f)); TextButton(onClick = { model.dismissError(); if (!state.ready) model.initialize() }) { Text("重试") }
                } }
            }
            if (!state.ready) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            when (tab) {
                0 -> {
                    if (state.messages.isEmpty()) {
                        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Column(Modifier.padding(20.dp)) {
                                Text("今天，想聊点什么？", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(8.dp))
                                Text("可以聊聊刚发生的小事，也可以把希望伙伴记住的事放进记忆。")
                                Text("伙伴形象准备中，当前可使用对话和记忆。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(state.messages, key = { it.id }) { message ->
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor =
                                if (message.role == MessageRole.USER) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(if (message.role == MessageRole.USER) "我" else state.profile.name, style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.height(6.dp))
                                    if (message.status == ReplyStatus.GENERATING) {
                                        Text("正在想怎么回应你…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                                    } else if (message.status != ReplyStatus.COMPLETE) {
                                        Text(message.error ?: "回应未完成")
                                        TextButton(onClick = { model.retry(message.id) }, enabled = !state.generating) { Text("重新回应") }
                                    } else {
                                        Text(message.text)
                                        TextButton(onClick = { memoryText = message.text.take(2000); memorySource = message.id; addMemory = true }) { Text("记住这件事") }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().imePadding().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(draft, model::draft, Modifier.weight(1f).testTag("pet-input"), label = { Text("和伙伴说说话") }, maxLines = 4)
                        Spacer(Modifier.width(8.dp))
                        if (state.generating) TextButton(onClick = { model.cancel() }) { Text("停止") }
                        else Button(onClick = model::send, enabled = draft.isNotBlank(), modifier = Modifier.testTag("pet-send")) { Text("发送") }
                    }
                }
                1 -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("共同记忆 · ${state.memories.size}", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { memoryText = ""; memorySource = null; addMemory = true }) { Text("添加记忆") }
                    }
                    if (state.memories.isEmpty()) Text("还没有记忆。添加你希望伙伴在以后交流中记住的事。", Modifier.padding(vertical = 24.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.memories, key = { it.id }) { memory ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                                Text(memory.text)
                                Text(if (memory.sourceMessageId == null) "手动添加" else "来自共同对话", style = MaterialTheme.typography.labelSmall)
                                Row { TextButton(onClick = { editMemory = memory; memoryText = memory.text }) { Text("更正") }
                                    TextButton(onClick = { deleteMemory = memory }) { Text("删除") } }
                            } }
                        }
                    }
                }
                else -> {
                    Text("伙伴设定", style = MaterialTheme.typography.titleMedium)
                    Text(state.profile.personality, Modifier.padding(vertical = 12.dp))
                    OutlinedButton(onClick = { editProfile = true }) { Text("修改名称与人格") }
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("在 App 内显示伙伴入口", Modifier.weight(1f))
                        Switch(state.profile.showInApp, { model.visible(it) })
                    }
                    Text("桌面陪伴", style = MaterialTheme.typography.titleMedium)
                    Text("开启后，可在桌面或其他 App 上与伙伴交流。", Modifier.padding(vertical = 12.dp))
                    Row { OutlinedButton(onClick = onOverlay) { Text("开启桌面陪伴") }
                        TextButton(onClick = onStopOverlay) { Text("关闭") } }
                }
            }
        }
    }
    if (addMemory || editMemory != null) AlertDialog(onDismissRequest = { addMemory = false; editMemory = null },
        title = { Text(if (editMemory == null) "添加共同记忆" else "更正记忆") },
        text = { OutlinedTextField(memoryText, { if (it.length <= 2000) memoryText = it }, label = { Text("希望伙伴记住的事") }) },
        confirmButton = { TextButton(enabled = memoryText.isNotBlank(), onClick = {
            editMemory?.let { model.edit(it, memoryText) } ?: model.memory(memoryText, memorySource)
            addMemory = false; editMemory = null
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { addMemory = false; editMemory = null }) { Text("取消") } })
    deleteMemory?.let { memory -> AlertDialog(onDismissRequest = { deleteMemory = null }, title = { Text("删除这条记忆？") },
        text = { Text("删除后，后续交流不再使用这条记忆；原对话仍保留。") },
        confirmButton = { TextButton(onClick = { model.delete(memory); deleteMemory = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteMemory = null }) { Text("取消") } }) }
    if (editProfile) ProfileDialog(state.profile, onDismiss = { editProfile = false }, onSave = { name, personality -> model.profile(name, personality); editProfile = false })
    if (showAi) SharedAiDialog(ai) { showAi = false }
}

@Composable private fun ProfileDialog(profile: CompanionProfile, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var personality by rememberSaveable { mutableStateOf(profile.personality) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("伙伴设定") }, text = { Column {
        OutlinedTextField(name, { if (it.length <= 40) name = it }, label = { Text("名字") })
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(personality, { if (it.length <= 4000) personality = it }, label = { Text("人格与相处方式") }, minLines = 3)
    } }, confirmButton = { TextButton(enabled = name.isNotBlank() && personality.isNotBlank(), onClick = { onSave(name, personality) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun SharedAiDialog(ai: AiServices, dismiss: () -> Unit) {
    val config by ai.configuration.configuration.collectAsStateWithLifecycle()
    var provider by rememberSaveable { mutableStateOf(AiProvider.GEMINI) }
    var rawKeys by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val summaries by ai.keys(provider).summaries.collectAsStateWithLifecycle()
    val storageError by ai.keys(provider).storageError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = dismiss, title = { Text("公共 AI 设置") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("与见域共用同一份 Key，伙伴对话可独立选择模型。") }
            items(AiModel.entries.filter { it.provider in AiUseCase.PET_REPLY.supportedProviders }) { model ->
                FilterChip(selected = config.modelFor(AiUseCase.PET_REPLY) == model, onClick = {
                    runCatching { ai.configuration.selectModel(AiUseCase.PET_REPLY, model) }.onFailure { result = "模型选择未保存，请重试。" }
                }, label = { Text(model.displayName) })
            }
            item { HorizontalDivider(); Row { AiProvider.entries.forEach { entry ->
                TextButton(onClick = { provider = entry; rawKeys = ""; result = null }) { Text(if (provider == entry) "✓ ${entry.displayName}" else entry.displayName) }
            } } }
            item { OutlinedTextField(rawKeys, { rawKeys = it }, label = { Text("导入 ${provider.displayName} Key") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), maxLines = 3) }
            item { Button(enabled = rawKeys.isNotBlank(), onClick = {
                val selectedProvider = provider
                val importedRaw = rawKeys
                rawKeys = ""
                scope.launch {
                    try {
                        val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.keys(selectedProvider).importBatch(importedRaw) }
                        result = "已导入 ${imported.added} 个，重复 ${imported.duplicates} 个。"
                        ai.validateKeys(selectedProvider, imported.importedIds)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { result = "Key 导入或校验未完成，请重试。" }
                }
            }) { Text("导入并校验") } }
            items(summaries, key = { it.id }) { key -> Text("${key.displayName}  ${key.maskedKey}  ${when (key.validationState) {
                ApiKeyValidationState.UNVERIFIED -> "未校验"
                ApiKeyValidationState.CHECKING -> "校验中"
                ApiKeyValidationState.AVAILABLE -> "可用"
                ApiKeyValidationState.INVALID -> "不可用"
                ApiKeyValidationState.NETWORK_ERROR -> "网络校验失败"
                ApiKeyValidationState.RATE_LIMITED -> "暂时限流"
            }}") }
            item { storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }; result?.let { Text(it) } }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } })
}
