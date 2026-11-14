package com.elio.jianyu.ui.screens.mine

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.jianyu.backup.*
import com.elio.jianyu.domain.JianyuRepository
import com.elio.jianyu.ui.components.JianyuPageShell
import com.elio.jianyu.ui.components.JianyuStateCard
import com.elio.jianyu.ui.components.JianyuTopBarLevel
import com.elio.jianyu.ui.lifecycle.sessionCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun BackupRoute(backup: JianyuBackupService, repository: JianyuRepository, onBack: () -> Unit) {
    var legacy by rememberSaveable { mutableStateOf(false) }
    BackHandler(legacy) { legacy = false }
    if (legacy) { LegacyBackupManagement(backup, repository, { legacy = false }); return }
    val complete = backup.complete
    val operation by complete.operation.collectAsStateWithLifecycle()
    val preview by complete.preview.collectAsStateWithLifecycle()
    val busy = operation is JianyuCompleteOperation.Busy
    val scope = sessionCoroutineScope()
    var pendingUri by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.jianyu.full-backup")) { uri ->
        if (uri != null) { exporting = true; pendingUri = uri.toString() }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { exporting = false; pendingUri = uri.toString() }
    }
    fun chooseExport() { export.launch("jianyu-complete-${System.currentTimeMillis()}.jyfull") }
    JianyuPageShell(title = "见域备份与恢复", level = JianyuTopBarLevel.SECONDARY,
        subtitle = "只覆盖见域，Life 笔记保持独立", onBack = onBack, contentScrollable = true,
        modifier = Modifier.testTag(BackupTestTags.SCREEN)) {
        JianyuStateCard("完整见域备份", "保留会话、消息、自定义角色、资料、个人背景、成果、音频、头像和可迁移设置。API Key、设备密钥和 Life 笔记不包含在内。")
        Button(onClick = ::chooseExport, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(BackupTestTags.CREATE)) { Text("创建完整见域备份") }
        OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag(BackupTestTags.RESTORE)) { Text("迁入或恢复见域") }
        Text("仅接受完整 .jyfull 文件。旧版有限 .jybak 或设备快照不能当作完整迁移包；旧 App 的资料不会被删除。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val state = operation) {
            is JianyuCompleteOperation.Busy -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.message) }
            is JianyuCompleteOperation.Finished -> JianyuStateCard("操作完成", state.message)
            is JianyuCompleteOperation.Failed -> JianyuStateCard("操作未完成", state.message)
            JianyuCompleteOperation.Idle -> Unit
        }
        localError?.let { JianyuStateCard("无法继续", it) }
        TextButton(onClick = { legacy = true }, enabled = !busy) { Text("管理历史有限备份与设备快照") }
    }
    pendingUri?.let { uri ->
        CompletePasswordDialog(exporting, onDismiss = { pendingUri = null }, onConfirm = { password ->
            pendingUri = null; localError = null
            if (exporting) complete.startExport(uri, password)
            else scope.launch {
                try { complete.inspect(uri, password) }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { localError = "无法读取或核对所选文件；当前见域尚未被替换。" }
            }
        })
    }
    if (pendingUri == null) preview?.let { item ->
        CompleteRestorePreview(item, busy, onDismiss = { scope.launch { complete.dismissPreview() } },
            onBackup = ::chooseExport, onRestore = { complete.startRestore(item.token, item.targetHasData) })
    }
}

@Composable
internal fun CompletePasswordDialog(exporting: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // 密码只留本次对话框内存，不写 SavedState、偏好或恢复包。
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (exporting) "设置完整备份密码" else "输入完整备份密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text("密码（至少 8 位）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("complete-password"))
                if (exporting) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("再次输入密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("complete-password-confirm"))
                Text("密码不会保存，遗忘后无法找回。请勿使用 API Key 作为备份密码。")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) },
            enabled = password.length >= 8 && password.toByteArray(Charsets.UTF_8).size <= 1024 && (!exporting || password == confirmation),
            modifier = Modifier.testTag("complete-password-submit")) {
            Text(if (exporting) "加密并保存" else "解密并预览")
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun CompleteRestorePreview(item: JianyuCompletePreview, busy: Boolean, onDismiss: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit) {
    var acknowledged by rememberSaveable(item.token) { mutableStateOf(false) }
    var allCounts by rememberSaveable(item.token) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("核对见域恢复范围") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("来源：${if (item.sourcePackage == "com.elio.jianyu") "旧见域" else "Life Archive 中的见域"} ${item.sourceVersion}")
                Text("会话 ${item.recordCounts["chat_sessions"] ?: 0} · 消息 ${item.recordCounts["messages"] ?: 0} · 角色 ${item.recordCounts["characters"] ?: 0}")
                Text("资料 ${item.recordCounts["material_references"] ?: 0} · 个人背景 ${item.recordCounts["personal_context_entries"] ?: 0} · 成果 ${item.recordCounts["confirmed_artifacts"] ?: 0}")
                Text("用户文件 ${item.attachmentCount} 个，${item.attachmentBytes / 1024} KiB；记录、标识、关联和文件校验已核对。")
                TextButton(onClick = { allCounts = !allCounts }) { Text(if (allCounts) "收起完整数量清单" else "查看完整数量清单") }
                if (allCounts) for ((table, count) in item.recordCounts) Text("${recordLabel(table)}：$count", style = MaterialTheme.typography.bodySmall)
                Text(item.notices.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                if (item.targetHasData && !item.targetBackupReady) {
                    Text("当前见域已有记录。请先创建当前内容的完整备份，再确认替换。", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onBackup, enabled = !busy) { Text("先备份当前见域") }
                } else if (item.targetHasData) Text("当前见域的完整备份已回读验证，可以确认替换。")
                Row {
                    Checkbox(acknowledged, { acknowledged = it }, enabled = !busy, modifier = Modifier.testTag("complete-restore-acknowledge"))
                    Text("我理解将替换见域内容与设置、清空未发送输入，并重新配置 API Key。Life 笔记不在覆盖范围内。", style = MaterialTheme.typography.bodySmall)
                }
                if (busy) Text("正在处理，请等待完成。")
            }
        },
        confirmButton = { TextButton(onClick = onRestore, enabled = !busy && acknowledged && item.targetBackupReady,
            modifier = Modifier.testTag("complete-restore-confirm")) { Text("替换并恢复见域") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

private fun recordLabel(name: String): String = mapOf(
    "characters" to "角色", "chat_sessions" to "会话", "messages" to "消息", "character_groups" to "角色分组",
    "conversation_summary_checkpoints" to "会话摘要", "issues" to "议题", "stages" to "阶段", "execution_runs" to "执行记录",
    "execution_participant_snapshots" to "执行角色快照", "execution_participant_states" to "执行角色状态", "execution_run_budgets" to "执行用量",
    "material_references" to "资料", "material_usage_snapshots" to "资料使用快照", "personal_context_entries" to "个人背景", "personal_context_usage_snapshots" to "背景使用快照",
    "skill_knowledge_usage_snapshots" to "角色知识使用快照", "stage_summary_drafts" to "成果草稿", "stage_summary_draft_revisions" to "草稿修订",
    "confirmed_artifacts" to "已保存成果", "artifact_message_sources" to "成果消息来源", "artifact_run_sources" to "成果执行来源", "artifact_draft_sources" to "成果草稿来源", "artifact_material_sources" to "成果资料来源",
    "audio_assets" to "音频记录", "official_skill_combinations" to "角色组合", "official_skill_combination_members" to "组合成员",
    "issue_lifecycle" to "议题状态", "issue_archive_events" to "议题归档", "issue_resume_events" to "议题继续记录", "issue_relations" to "议题关联", "issue_purge_operations" to "彻底清除记录",
    "cross_discussion_sessions" to "交叉讨论", "execution_message_usage_snapshots" to "执行消息快照", "stage_advancements" to "阶段推进",
    "stage_advancement_measures" to "推进措施", "stage_advancement_skill_members" to "推进角色", "stage_advancement_materials" to "继承资料", "stage_advancement_artifacts" to "继承成果",
)[name] ?: "其他记录"
