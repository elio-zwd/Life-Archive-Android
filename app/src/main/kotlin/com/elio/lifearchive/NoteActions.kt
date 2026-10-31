package com.elio.lifearchive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.elio.lifearchive.domain.notes.LibraryNote

internal enum class NoteAction { PIN, COVER, RENAME, TAGS, TRASH, RESTORE, PURGE, MARKDOWN, ZIP }
@Composable internal fun NoteActions(note: LibraryNote, onDismiss: () -> Unit, onAction: (NoteAction, String) -> Unit) {
    var editing by remember(note.summary.id) { mutableStateOf<NoteAction?>(null) }
    var text by remember(note.summary.id) { mutableStateOf("") }
    var confirmPurge by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(note.summary.path.value) }, text = {
        Column {
            if (editing != null) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(if (editing == NoteAction.TAGS) "属性标签，以逗号分隔" else "完整库内路径（含 .md）") }, singleLine = true)
                if (editing == NoteAction.TAGS) Text("这里只修改属性中的 tags；正文 #标签 可在源码中修改。")
                TextButton(onClick = { onAction(editing!!, text) }) { Text("确认") }
            } else if (confirmPurge) {
                Text("永久删除这篇笔记及其草稿，无法从回收站找回。共享附件保留。")
                TextButton(onClick = { onAction(NoteAction.PURGE, "") }) { Text("确认永久删除") }
            } else if (note.deleted != null) {
                TextButton(onClick = { editing = NoteAction.RESTORE; text = note.summary.path.value }) { Text("恢复（可更换路径）") }
                TextButton(onClick = { confirmPurge = true }) { Text("永久删除…") }
            } else {
                TextButton(onClick = { onAction(NoteAction.PIN, "") }) { Text(if (note.pinned) "取消置顶" else "置顶") }
                TextButton(onClick = { onAction(NoteAction.COVER, "") }) { Text("设置封面") }
                TextButton(onClick = { editing = NoteAction.RENAME; text = note.summary.path.value }) { Text("重命名／移动") }
                TextButton(onClick = { editing = NoteAction.TAGS; text = note.propertyTags.joinToString(", ") }) { Text("管理属性标签") }
                TextButton(onClick = { onAction(NoteAction.MARKDOWN, "") }) { Text("导出 Markdown 原文") }
                TextButton(onClick = { onAction(NoteAction.ZIP, "") }) { Text("导出关联资料 ZIP") }
                TextButton(onClick = { onAction(NoteAction.TRASH, "") }) { Text("移入回收站") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
