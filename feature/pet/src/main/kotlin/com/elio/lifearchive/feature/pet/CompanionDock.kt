package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.domain.pet.*

/** 与伙伴页使用同一 Activity ViewModel 和 Repository；展开窗口不另建对话。 */
@Composable
fun CompanionDock(repository: CompanionRepository, expanded: Boolean, onExpand: (Boolean) -> Unit, onOpen: () -> Unit, onHide: () -> Unit) {
    val model = companionViewModel(repository)
    val state by model.state.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(touched) { if (touched) { kotlinx.coroutines.delay(2500); touched = false } }
    val last = state.messages.lastOrNull { it.role == MessageRole.COMPANION }
    val bubble = if (touched) "嗯，我在。" else if (state.generating) "正在想怎么回应你…" else last?.error ?: last?.text ?: "慢慢来，我在这里。"
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.Bottom) {
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
                Text(bubble.take(if (expanded) 180 else 60), Modifier.padding(10.dp), maxLines = if (expanded) 5 else 3, style = MaterialTheme.typography.bodySmall)
            }
            CompanionAvatar(state, Modifier.width(if (expanded) 104.dp else 72.dp).height(if (expanded) 144.dp else 154.dp), onTap = { touched = true })
        }
        if (expanded) Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 4.dp, modifier = Modifier.testTag("in-app-companion-dialogue")) {
            Column(Modifier.padding(10.dp).imePadding()) {
                var input by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
                LaunchedEffect(draft) {
                    if (input.text != draft) input = TextFieldValue(draft, TextRange(draft.length))
                }
                OutlinedTextField(input, { if (it.text.length <= 16_000) { input = it; model.draft(it.text) } },
                    Modifier.fillMaxWidth().testTag("in-app-companion-input"), label = { Text("和伙伴说说话") }, maxLines = 3)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.generating) TextButton(onClick = { model.cancel() }) { Text("停止") }
                    else TextButton(onClick = model::send, enabled = draft.isNotBlank()) { Text("发送") }
                    TextButton(onClick = onOpen) { Text("回到伙伴") }
                    TextButton(onClick = { onExpand(false) }) { Text("结束对话") }
                    TextButton(onClick = onHide) { Text("收起") }
                }
            }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { onExpand(true) }, modifier = Modifier.testTag("in-app-companion-chat")) { Text("对话") }
            TextButton(onClick = onHide) { Text("收起") }
        }
    }
}
