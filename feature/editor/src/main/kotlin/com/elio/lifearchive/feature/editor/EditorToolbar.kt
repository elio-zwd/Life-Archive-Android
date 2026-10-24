package com.elio.lifearchive.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.elio.lifearchive.core.document.*
import kotlinx.coroutines.flow.collect

class EditorToolActions(
    val command: (MarkdownCommand, EditorAnchor?) -> Unit,
    val begin: (EditorInsertKind) -> Unit,
    val dismiss: () -> Unit,
    val reanchor: () -> Unit,
    val edit: ((EditorInsertion) -> EditorInsertion) -> Unit,
    val search: (String) -> Unit,
    val external: (String, String) -> Unit,
    val template: (String) -> Unit,
    val note: (NoteLinkChoice, String, Boolean) -> Unit,
    val reinsertAttachment: () -> Unit,
)

private enum class ToolsPanel { LIST, FORMAT, INSERT }
private data class ToolItem(val label: String, val action: () -> Unit, val enabled: Boolean = true, val selected: Boolean = false)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorToolbar(session: EditorSession, enabled: Boolean, parsed: ParsedDocument?, onCommand: (MarkdownCommand, EditorAnchor?) -> Unit, onInsert: (EditorInsertKind) -> Unit, onAttach: () -> Unit) {
    var panel by rememberSaveable(session) { mutableStateOf<ToolsPanel?>(null) }
    val snapshot = session.content
    val source = snapshot.source
    val selection = session.text.selection
    val display = remember(session, parsed) { parsed?.let(session::display) }
    val selectedStyles = remember(snapshot, selection, display) {
        val changes = display?.let { session.changesSince(it.revision) }
        if (display != null && changes != null) display.styles((selection.min - 1).coerceAtLeast(0)..selection.max, changes, source.length) else emptyList()
    }
    val tools = remember(snapshot, selection, selectedStyles) {
        MarkdownEditing.tools(source, EditSelection(selection.start, selection.end), ParsedDocument(source, emptyList(), selectedStyles, emptyList()))
    }
    val editable = enabled && session.canModify
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    fun send(command: MarkdownCommand) { onCommand(command, session.anchor()) }
    fun toggle(value: ToolsPanel) { panel = if (panel == value) null else value }
    val items = when (panel) {
        ToolsPanel.LIST -> listOf(
            ToolItem("无序列表", { send(MarkdownCommand.List(ListStyle.BULLET)) }, selected = tools.list == ListStyle.BULLET),
            ToolItem("有序列表", { send(MarkdownCommand.List(ListStyle.ORDERED)) }, selected = tools.list == ListStyle.ORDERED),
            ToolItem("待办清单", { send(MarkdownCommand.List(ListStyle.TASK)) }, selected = tools.list == ListStyle.TASK),
            ToolItem("勾选／取消", { send(MarkdownCommand.ToggleTask) }, tools.hasTask),
            ToolItem("增加缩进", { send(MarkdownCommand.Indent(1)) }, tools.canIndent),
            ToolItem("减少缩进", { send(MarkdownCommand.Indent(-1)) }, tools.canIndent),
            ToolItem("引用", { send(MarkdownCommand.Quote) }),
        )
        ToolsPanel.FORMAT -> listOf(
            "加粗" to InlineStyle.BOLD, "斜体" to InlineStyle.ITALIC, "删除线" to InlineStyle.STRIKE,
            "高亮" to InlineStyle.HIGHLIGHT, "行内代码" to InlineStyle.CODE,
        ).map { (label, style) ->
            val mark = when (style) { InlineStyle.BOLD -> TextMark.BOLD; InlineStyle.ITALIC -> TextMark.ITALIC; InlineStyle.STRIKE -> TextMark.STRIKE; InlineStyle.HIGHLIGHT -> TextMark.HIGHLIGHT; InlineStyle.CODE -> TextMark.CODE }
            val selected = selectedStyles.any { it.mark == mark && selection.min >= it.start && selection.max <= it.end }
            ToolItem(label, { send(MarkdownCommand.Inline(style)) }, selected = selected)
        }
        ToolsPanel.INSERT -> listOf(
            ToolItem("链接", { onInsert(EditorInsertKind.EXTERNAL_LINK) }),
            ToolItem("双链", { onInsert(EditorInsertKind.WIKI_LINK) }),
            ToolItem("附件", onAttach),
            ToolItem("引用", { send(MarkdownCommand.Quote) }),
            ToolItem("表格", { send(MarkdownCommand.Template(InsertTemplate.TABLE)) }),
            ToolItem("代码块", { onInsert(EditorInsertKind.CODE_BLOCK) }),
            ToolItem("分隔线", { send(MarkdownCommand.Template(InsertTemplate.DIVIDER)) }),
            ToolItem("Callout", { onInsert(EditorInsertKind.CALLOUT) }),
            ToolItem("行内公式", { send(MarkdownCommand.Template(InsertTemplate.INLINE_MATH)) }),
            ToolItem("块级公式", { send(MarkdownCommand.Template(InsertTemplate.BLOCK_MATH)) }),
            ToolItem("Mermaid", { send(MarkdownCommand.Template(InsertTemplate.MERMAID)) }),
            ToolItem("脚注", { send(MarkdownCommand.Template(InsertTemplate.FOOTNOTE)) }),
        )
        null -> emptyList()
    }
    Surface(color = colors.surfaceContainer, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            if (panel != null) LazyHorizontalGrid(rows = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(96.dp).testTag("editor-tool-panel"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(items, key = { it.label }) { item ->
                    TextButton(onClick = item.action, enabled = editable && item.enabled, modifier = Modifier.width(100.dp).heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (item.selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) { Text(item.label, maxLines = 2) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = session::undo, enabled = editable && session.text.undoState.canUndo, modifier = Modifier.width(48.dp).semantics { contentDescription = "撤销" }) { Text("↶") }
                TextButton(onClick = session::redo, enabled = editable && session.text.undoState.canRedo, modifier = Modifier.width(48.dp).semantics { contentDescription = "重做" }) { Text("↷") }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    // 正文或选区变化会销毁旧手势节点，取消尚未判定的单击。
                    val target = remember(snapshot.revision, selection, editable) { Any() }
                    key(session, target) { HeadingTool(session, editable, tools.heading, onCommand) }
                    TextButton(onClick = { toggle(ToolsPanel.LIST) }, modifier = Modifier.widthIn(min = 48.dp).testTag("tool-list")) { Text("列表") }
                    TextButton(onClick = { toggle(ToolsPanel.FORMAT) }, modifier = Modifier.widthIn(min = 48.dp).testTag("tool-format")) { Text("格式") }
                    TextButton(onClick = { toggle(ToolsPanel.INSERT) }, modifier = Modifier.widthIn(min = 48.dp).testTag("tool-insert")) { Text("插入") }
                }
                TextButton(onClick = { keyboard?.hide() }, modifier = Modifier.width(48.dp).semantics { contentDescription = "收起键盘" }) { Text("⌄") }
            }
            if (!session.canModify) Text("请先确认输入中的文字", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeadingTool(session: EditorSession, enabled: Boolean, level: Int?, onCommand: (MarkdownCommand, EditorAnchor?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<EditorAnchor?>(null) }
    val interactions = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val label = when (level) { null -> "混合"; 0 -> "标题"; else -> "H$level" }
    fun send(command: MarkdownCommand) { onCommand(command, anchor ?: session.anchor()) }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { event ->
            if (event is PressInteraction.Press) anchor = session.anchor()
            if (event is PressInteraction.Cancel) anchor = null
        }
    }
    Box {
        Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("tool-heading")
            .combinedClickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button,
                onClick = { send(MarkdownCommand.AdjustHeading(1)) }, onDoubleClick = { send(MarkdownCommand.AdjustHeading(-1)) },
                onLongClickLabel = "选择标题级别", onLongClick = { anchor = session.anchor(); haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
            .semantics {
                contentDescription = "标题，当前${if (level == 0) "正文" else label}；单击加深，双击减浅，长按选择"
                customActions = listOf(
                    CustomAccessibilityAction("加深标题") { send(MarkdownCommand.AdjustHeading(1)); true },
                    CustomAccessibilityAction("减浅标题") { send(MarkdownCommand.AdjustHeading(-1)); true },
                    CustomAccessibilityAction("选择标题级别") { anchor = session.anchor(); menu = true; true },
                )
            }, contentAlignment = Alignment.Center) { Text(label, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = false)) {
            Text("单击加深 · 双击减浅", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
            (0..6).forEach { value -> DropdownMenuItem(text = { Text(if (value == 0) "正文" else "H$value") }, onClick = { menu = false; send(MarkdownCommand.Heading(value)) }) }
        }
    }
}
