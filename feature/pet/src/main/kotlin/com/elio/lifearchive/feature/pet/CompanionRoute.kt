package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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


@Composable
fun CompanionRoute(
    repository: CompanionRepository,
    ai: AiServices,
    onBottomBarMeasured: (Int) -> Unit,
    onOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onInAppVisibility: (Boolean) -> Unit = {},
    openChatRequest: Long = 0,
    openSettingsRequest: Long = 0,
) {
    val model = companionViewModel(repository)
    val state by model.state.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var handledChatRequest by rememberSaveable { mutableLongStateOf(0) }
    var handledSettingsRequest by rememberSaveable { mutableLongStateOf(0) }
    var desktopSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openSettingsRequest) {
        if (openSettingsRequest > handledSettingsRequest) { tab = 2; handledSettingsRequest = openSettingsRequest }
    }
    LaunchedEffect(openChatRequest) {
        if (openChatRequest > handledChatRequest) { tab = 0; handledChatRequest = openChatRequest }
    }
    var memoryEditorOpen by rememberSaveable { mutableStateOf(false) }
    var memorySubmissionId by rememberSaveable { mutableStateOf("") }
    val savedMemorySubmission by model.savedMemorySubmission.collectAsStateWithLifecycle()
    LaunchedEffect(savedMemorySubmission, memoryEditorOpen) {
        if (memoryEditorOpen && savedMemorySubmission == memorySubmissionId) memoryEditorOpen = false
    }
    var memoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var memoryRevision by rememberSaveable { mutableLongStateOf(0L) }
    var memorySource by rememberSaveable { mutableStateOf<String?>(null) }
    var memoryText by rememberSaveable { mutableStateOf("") }
    var deleteMemory by remember { mutableStateOf<CompanionMemory?>(null) }
    var showAi by rememberSaveable { mutableStateOf(false) }
    var editProfile by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.profile.showInApp) { onInAppVisibility(state.profile.showInApp) }
    Scaffold(Modifier.fillMaxSize().testTag("pet-root"), bottomBar = {
        NavigationBar(Modifier.onSizeChanged { onBottomBarMeasured(it.height) }) {
            listOf("陪伴", "记忆", "设置").forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { focus.clearFocus(); tab = index }, modifier = Modifier.testTag("pet-tab-$index"),
                    icon = { Text(listOf("◉", "♡", "☷")[index]) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).statusBarsPadding().fillMaxSize()) {
          if (tab == 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(state.profile.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("日常里的陪伴", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { showAi = true }) { Text("公共 AI") }
            }
          }
            if (error != null) {
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error.orEmpty(), Modifier.weight(1f)); TextButton(onClick = { model.dismissError(); if (!state.ready) model.initialize() }) { Text("重试") }
                } }
            }
            if (!state.ready) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            when (tab) {
                0 -> {
                    CompanionConversation(state, draft, model::draft, model::send, { model.cancel() }, { model.retry(it) },
                        onRemember = { message ->
                            focus.clearFocus(); model.dismissError(); memoryId = null; memorySource = message.id
                            memoryText = message.text.take(2000); memorySubmissionId = java.util.UUID.randomUUID().toString(); memoryEditorOpen = true
                        }, avatar = { modifier, tap -> CompanionAvatar(state, modifier, tap) })
                }
                1 -> {
                    CompanionMemories(
                        state = state,
                        onAdd = { focus.clearFocus(); model.dismissError(); memoryId = null; memoryText = ""; memorySource = null; memorySubmissionId = java.util.UUID.randomUUID().toString(); memoryEditorOpen = true },
                        onEdit = { focus.clearFocus(); model.dismissError(); memoryId = it.id; memoryRevision = it.revision; memorySource = it.sourceMessageId; memoryText = it.text; memorySubmissionId = java.util.UUID.randomUUID().toString(); memoryEditorOpen = true },
                        onDelete = { deleteMemory = it },
                    )
                }
                else -> {
                    CompanionSettings(state, { focus.clearFocus(); model.dismissError(); editProfile = true }, { model.visible(it) }, onOverlay, onStopOverlay,
                        { showAi = true }, avatar = { CompanionAvatar(state, it, allowParentScroll = true) }, onDesktopSettings = { desktopSettings = true })
                }
            }
        }
    }
    if (memoryEditorOpen) MemoryEditorSheet(memoryText, { memoryText = it }, memoryId != null, memorySource != null,
        onDismiss = { memoryEditorOpen = false }, errorMessage = error, onSave = {
            model.saveMemory(memoryId?.let { CompanionMemory(it, memoryText, memorySource, memoryRevision) }, memoryText, memorySource, memorySubmissionId)
        })
    deleteMemory?.let { memory -> AlertDialog(onDismissRequest = { deleteMemory = null }, title = { Text("删除这条记忆？") },
        text = { Text("删除后，后续交流不再使用这条记忆；原对话仍保留。") },
        confirmButton = { TextButton(onClick = { model.delete(memory); deleteMemory = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteMemory = null }) { Text("取消") } }) }
    if (editProfile) ProfileEditorSheet(state.profile, onDismiss = { editProfile = false }, errorMessage = error, onSave = model::saveProfile)
    if (showAi) SharedAiDialog(ai, model) { showAi = false }
    if (desktopSettings) DesktopCompanionSettings(state.overlay, { desktopSettings = false }, model::overlaySettings)
}

@Composable
internal fun companionViewModel(repository: CompanionRepository): CompanionViewModel = viewModel(factory = remember(repository) {
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CompanionViewModel(repository) as T
    }
})

@Composable private fun SharedAiDialog(ai: AiServices, viewModel: CompanionViewModel, dismiss: () -> Unit) {
    val config by ai.configuration.configuration.collectAsStateWithLifecycle()
    var provider by rememberSaveable { mutableStateOf(AiProvider.GEMINI) }
    var useCase by rememberSaveable { mutableStateOf(AiUseCase.PET_REPLY) }
    var rawKeys by remember { mutableStateOf("") }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val importResult by viewModel.aiImportResult.collectAsStateWithLifecycle()
    val summaries by ai.keys(provider).summaries.collectAsStateWithLifecycle()
    val storageError by ai.keys(provider).storageError.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = dismiss, title = { Text("公共 AI 设置") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("与见域共用同一份 Key，伙伴对话可独立选择模型。") }
            item { Row { listOf(AiUseCase.PET_REPLY, AiUseCase.PET_GREETING).forEach { purpose ->
                FilterChip(selected = useCase == purpose, onClick = { useCase = purpose }, label = { Text(purpose.displayName) })
            } } }
            items(AiModel.entries.filter { it.provider in useCase.supportedProviders }) { model ->
                FilterChip(selected = config.modelFor(useCase) == model, onClick = {
                    runCatching { ai.configuration.selectModel(useCase, model) }
                        .onSuccess { selectionError = null }
                        .onFailure { selectionError = "模型选择未保存，请重试。" }
                }, label = { Text(model.displayName) })
            }
            item { HorizontalDivider(); Row { AiProvider.entries.forEach { entry ->
                TextButton(onClick = { provider = entry; rawKeys = ""; selectionError = null; viewModel.clearAiImportResult() }) {
                    Text(if (provider == entry) "✓ ${entry.displayName}" else entry.displayName)
                }
            } } }
            item { OutlinedTextField(rawKeys, { rawKeys = it }, label = { Text("导入 ${provider.displayName} Key") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), maxLines = 3) }
            item { Button(enabled = rawKeys.isNotBlank(), onClick = {
                val selectedProvider = provider
                val importedRaw = rawKeys
                rawKeys = ""
                selectionError = null
                viewModel.importAndValidateKeys(ai, selectedProvider, importedRaw)
            }) { Text("导入并校验") } }
            items(summaries, key = { it.id }) { key -> Text("${key.displayName}  ${key.maskedKey}  ${when (key.validationState) {
                ApiKeyValidationState.UNVERIFIED -> "未校验"
                ApiKeyValidationState.CHECKING -> "校验中"
                ApiKeyValidationState.AVAILABLE -> "可用"
                ApiKeyValidationState.INVALID -> "不可用"
                ApiKeyValidationState.NETWORK_ERROR -> "网络校验失败"
                ApiKeyValidationState.RATE_LIMITED -> "暂时限流"
            }}") }
            item {
                storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                (selectionError ?: importResult)?.let { Text(it) }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } })
}
