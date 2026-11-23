package com.elio.lifearchive.feature.pet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.domain.pet.CompanionProfile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionEditorSheet(
    title: String,
    valid: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend () -> Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !saving })
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.85f
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding().padding(horizontal = 20.dp).testTag("pet-editor-sheet")) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            if (failed) Text("未保存，请核对后重试；若记忆已变化，请重新打开。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    saving = true; failed = false
                    scope.launch {
                        try { if (onSave()) onDismiss() else failed = true }
                        finally { saving = false }
                    }
                }, enabled = valid && !saving, modifier = Modifier.testTag("pet-editor-save")) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }
}

@Composable
internal fun MemoryEditorSheet(text: String, onText: (String) -> Unit, editing: Boolean, fromConversation: Boolean, onDismiss: () -> Unit, onSave: suspend () -> Boolean) {
    CompanionEditorSheet(if (editing) "更正记忆" else "添加共同记忆", text.isNotBlank(), onDismiss, onSave) {
        Text(if (fromConversation) "来自共同对话" else "手动添加", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(text, { if (it.length <= 2000) onText(it) }, Modifier.fillMaxWidth().testTag("pet-memory-text"),
            label = { Text("希望伙伴记住的事") }, minLines = 3, maxLines = 8)
        Text(if (editing) "只更正这条记忆，原对话仍保留。" else "保存后，伙伴可以在后续交流中参考这条记忆。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ProfileEditorSheet(profile: CompanionProfile, onDismiss: () -> Unit, onSave: suspend (String, String) -> Boolean) {
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var personality by rememberSaveable { mutableStateOf(profile.personality) }
    CompanionEditorSheet("伙伴设定", name.isNotBlank() && personality.isNotBlank(), onDismiss, { onSave(name, personality) }) {
        OutlinedTextField(name, { if (it.length <= 40) name = it }, Modifier.fillMaxWidth().testTag("pet-profile-name"), label = { Text("名字") }, singleLine = true)
        OutlinedTextField(personality, { if (it.length <= 4000) personality = it }, Modifier.fillMaxWidth().testTag("pet-profile-personality"),
            label = { Text("人格与相处方式") }, minLines = 3, maxLines = 8)
    }
}
