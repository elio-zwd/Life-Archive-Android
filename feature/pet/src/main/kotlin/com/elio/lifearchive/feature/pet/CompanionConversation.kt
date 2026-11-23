package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.domain.pet.*

@Composable
internal fun CompanionConversation(
    state: CompanionState,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onRetry: (String) -> Unit,
    onRemember: (CompanionMessage) -> Unit,
    avatar: @Composable (Modifier, () -> Unit) -> Unit,
) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(touched) { if (touched) { kotlinx.coroutines.delay(2500); touched = false } }
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.lastOrNull()?.id, state.generating) {
        if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex)
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().testTag("pet-conversation")) {
        val stageFits = !keyboardOpen && maxHeight >= 280.dp
        val showStage = !collapsed && stageFits
        val stageHeight = maxHeight * 0.68f
        val panelHeight = maxHeight * 0.48f
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface)))) {
            if (showStage) avatar(Modifier.fillMaxWidth().height(stageHeight).align(Alignment.TopCenter).testTag("pet-large-avatar")) { touched = true }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (touched) "嗯，我在。" else "Hiyori · 技术样板", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    if (collapsed || stageFits) TextButton(onClick = { collapsed = !collapsed }, modifier = Modifier.testTag("pet-avatar-toggle")) { Text(if (collapsed) "展开形象" else "收起形象") }
                }
                if (showStage) Spacer(Modifier.weight(1f))
                Surface(
                    modifier = Modifier.fillMaxWidth().then(if (showStage) Modifier.height(panelHeight) else Modifier.weight(1f)).testTag("pet-dialogue-panel"),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = colors.surface.copy(alpha = 0.94f), tonalElevation = 2.dp,
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp).size(32.dp, 3.dp).background(colors.outlineVariant, RoundedCornerShape(2.dp)))
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                            if (state.messages.isEmpty()) item(key = "welcome") {
                                Column(Modifier.padding(8.dp)) {
                                    Text("今天，想聊点什么？", style = MaterialTheme.typography.titleMedium)
                                    Text("聊聊日常，也可以把重要的小事留在共同记忆里。", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                }
                            }
                            items(state.messages, key = { it.id }) { message ->
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start) {
                                    Text(if (message.role == MessageRole.USER) "我" else state.profile.name, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    Surface(shape = RoundedCornerShape(18.dp), color = if (message.role == MessageRole.USER) colors.secondaryContainer else colors.surfaceContainer) {
                                        Column(Modifier.padding(12.dp)) {
                                            when (message.status) {
                                                ReplyStatus.GENERATING -> { Text("正在想怎么回应你…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
                                                ReplyStatus.COMPLETE -> Text(message.text)
                                                else -> {
                                                    Text(message.error ?: "回应未完成")
                                                    TextButton(onClick = { onRetry(message.id) }, enabled = !state.generating) { Text("重新回应") }
                                                }
                                            }
                                        }
                                    }
                                    if (message.status == ReplyStatus.COMPLETE) TextButton(onClick = { onRemember(message) }) { Text("记住这件事") }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().imePadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(draft, onDraft, Modifier.weight(1f).testTag("pet-input"), label = { Text("和伙伴说说话") }, maxLines = 3, shape = RoundedCornerShape(20.dp))
                            Spacer(Modifier.width(8.dp))
                            if (state.generating) TextButton(onClick = onCancel) { Text("停止") }
                            else Button(onClick = onSend, enabled = draft.isNotBlank(), modifier = Modifier.testTag("pet-send")) { Text("发送") }
                        }
                    }
                }
            }
        }
    }
}
