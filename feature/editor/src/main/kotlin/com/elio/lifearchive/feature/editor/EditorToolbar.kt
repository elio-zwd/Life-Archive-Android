package com.elio.lifearchive.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.elio.lifearchive.core.designsystem.LocalDocumentColors
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
private data class ToolItem(val label: String, val glyph: String, val action: () -> Unit, val enabled: Boolean = true, val selected: Boolean = false)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorToolbar(session: EditorSession, enabled: Boolean, parsed: ParsedDocument?, onCommand: (MarkdownCommand, EditorAnchor?) -> Unit, onInsert: (EditorInsertKind) -> Unit, onAttach: () -> Unit) {
    var panel by rememberSaveable(session) { mutableStateOf<ToolsPanel?>(null) }
    var hint by remember(session) { mutableStateOf<String?>(null) }
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
    val colors = LocalDocumentColors.current
    fun send(command: MarkdownCommand) { onCommand(command, session.anchor()) }
    fun toggle(value: ToolsPanel) { hint = null; panel = if (panel == value) null else value }
    fun showHint(label: String) { hint = label }
    fun hideHint(label: String) { if (hint == label) hint = null }
    val items = when (panel) {
        ToolsPanel.LIST -> listOf(
            ToolItem("无序列表", "•≡", { send(MarkdownCommand.List(ListStyle.BULLET)) }, selected = tools.list == ListStyle.BULLET),
            ToolItem("有序列表", "1≡", { send(MarkdownCommand.List(ListStyle.ORDERED)) }, selected = tools.list == ListStyle.ORDERED),
            ToolItem("待办清单", "☑", { send(MarkdownCommand.List(ListStyle.TASK)) }, selected = tools.list == ListStyle.TASK),
            ToolItem("勾选／取消", "✓", { send(MarkdownCommand.ToggleTask) }, tools.hasTask),
            ToolItem("增加缩进", "⇥", { send(MarkdownCommand.Indent(1)) }, tools.canIndent),
            ToolItem("减少缩进", "⇤", { send(MarkdownCommand.Indent(-1)) }, tools.canIndent),
            ToolItem("引用", "❝", { send(MarkdownCommand.Quote) }),
        )
        ToolsPanel.FORMAT -> listOf(
            Triple("加粗", "B", InlineStyle.BOLD), Triple("斜体", "I", InlineStyle.ITALIC),
            Triple("删除线", "S", InlineStyle.STRIKE), Triple("高亮", "▰", InlineStyle.HIGHLIGHT),
            Triple("行内代码", "</>", InlineStyle.CODE),
        ).map { (label, glyph, style) ->
            val mark = when (style) { InlineStyle.BOLD -> TextMark.BOLD; InlineStyle.ITALIC -> TextMark.ITALIC; InlineStyle.STRIKE -> TextMark.STRIKE; InlineStyle.HIGHLIGHT -> TextMark.HIGHLIGHT; InlineStyle.CODE -> TextMark.CODE }
            val selected = selectedStyles.any { it.mark == mark && selection.min >= it.start && selection.max <= it.end }
            ToolItem(label, glyph, { send(MarkdownCommand.Inline(style)) }, selected = selected)
        } + ToolItem("清除行内格式", "Tx", { send(MarkdownCommand.ClearInline) })
        ToolsPanel.INSERT -> listOf(
            ToolItem("链接", "↗", { onInsert(EditorInsertKind.EXTERNAL_LINK) }),
            ToolItem("双链", "[[]]", { onInsert(EditorInsertKind.WIKI_LINK) }),
            ToolItem("附件", "⌁", onAttach),
            ToolItem("引用", "❝", { send(MarkdownCommand.Quote) }),
            ToolItem("表格", "▦", { send(MarkdownCommand.Template(InsertTemplate.TABLE)) }),
            ToolItem("代码块", "{ }", { onInsert(EditorInsertKind.CODE_BLOCK) }),
            ToolItem("分隔线", "―", { send(MarkdownCommand.Template(InsertTemplate.DIVIDER)) }),
            ToolItem("提示块", "▣", { onInsert(EditorInsertKind.CALLOUT) }),
            ToolItem("行内公式", "x²", { send(MarkdownCommand.Template(InsertTemplate.INLINE_MATH)) }),
            ToolItem("块级公式", "∑", { send(MarkdownCommand.Template(InsertTemplate.BLOCK_MATH)) }),
            ToolItem("流程图", "◇", { send(MarkdownCommand.Template(InsertTemplate.MERMAID)) }),
            ToolItem("脚注", "[1]", { send(MarkdownCommand.Template(InsertTemplate.FOOTNOTE)) }),
        )
        null -> emptyList()
    }
    Surface(color = colors.background, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = colors.line)
            if (panel != null) key(panel) {
                val scroll = rememberLazyListState()
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(
                        modifier = Modifier.weight(1f).height(52.dp).testTag("editor-tool-panel"),
                        state = scroll,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(items, key = { it.label }) { item ->
                            ToolButton(item.label, item.glyph, editable && item.enabled, item.selected, hint, ::showHint, ::hideHint, item.action, toolBackground = true)
                        }
                    }
                    if (scroll.canScrollForward) {
                        Canvas(
                            Modifier.width(18.dp).height(52.dp).testTag("editor-tool-overflow").clearAndSetSemantics {
                                contentDescription = "向右滑动查看更多工具"
                            }
                        ) {
                            val x = size.width / 2f
                            val y = size.height / 2f
                            val width = 4.dp.toPx()
                            drawLine(colors.accent, Offset(x - width / 2, y - width), Offset(x + width / 2, y), 1.5.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(colors.accent, Offset(x + width / 2, y), Offset(x - width / 2, y + width), 1.5.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
                HorizontalDivider(color = colors.line)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton("撤销", "↶", editable && session.text.undoState.canUndo, false, hint, ::showHint, ::hideHint, session::undo)
                ToolButton("重做", "↷", editable && session.text.undoState.canRedo, false, hint, ::showHint, ::hideHint, session::redo)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    // 正文或选区变化会销毁旧手势节点，取消尚未判定的单击。
                    val target = remember(snapshot.revision, selection, editable) { Any() }
                    key(session, target) { HeadingTool(session, editable, tools.heading, hint, ::showHint, ::hideHint, onCommand) }
                    ToolButton("列表", "列表", true, panel == ToolsPanel.LIST, hint, ::showHint, ::hideHint, { toggle(ToolsPanel.LIST) }, modifier = Modifier.testTag("tool-list"))
                    ToolButton("格式", "格式", true, panel == ToolsPanel.FORMAT, hint, ::showHint, ::hideHint, { toggle(ToolsPanel.FORMAT) }, modifier = Modifier.testTag("tool-format"))
                    ToolButton("插入", "插入", true, panel == ToolsPanel.INSERT, hint, ::showHint, ::hideHint, { toggle(ToolsPanel.INSERT) }, modifier = Modifier.testTag("tool-insert"))
                }
                ToolButton("收起键盘", "⌄", true, false, hint, ::showHint, ::hideHint, { keyboard?.hide() })
            }
            if (!session.canModify) Text("请先确认输入中的文字", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun HeadingTool(session: EditorSession, enabled: Boolean, level: Int?, hint: String?, onShowHint: (String) -> Unit, onHideHint: (String) -> Unit, onCommand: (MarkdownCommand, EditorAnchor?) -> Unit) {
    var anchor by remember(session) { mutableStateOf<EditorAnchor?>(null) }
    val label = when (level) { null -> "混合"; 0 -> "标题"; else -> "H$level" }
    fun send(command: MarkdownCommand) { onCommand(command, anchor ?: session.anchor()) }
    ToolButton(
        label = "标题", glyph = label, enabled = enabled, selected = false, hint = hint,
        onShowHint = onShowHint, onHideHint = onHideHint,
        onClick = { send(MarkdownCommand.AdjustHeading(1)) },
        onDoubleClick = { send(MarkdownCommand.AdjustHeading(-1)) },
        onPress = { anchor = session.anchor() }, onCancel = { anchor = null },
        description = "标题，当前${if (level == 0) "正文" else label}；单击加深，双击减浅",
        actions = if (enabled) listOf(
            CustomAccessibilityAction("加深标题") { send(MarkdownCommand.AdjustHeading(1)); true },
            CustomAccessibilityAction("减浅标题") { send(MarkdownCommand.AdjustHeading(-1)); true },
        ) else emptyList(),
        modifier = Modifier.testTag("tool-heading"),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    label: String,
    glyph: String,
    enabled: Boolean,
    selected: Boolean,
    hint: String?,
    onShowHint: (String) -> Unit,
    onHideHint: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDoubleClick: (() -> Unit)? = null,
    onPress: () -> Unit = {},
    onCancel: () -> Unit = {},
    description: String = label,
    actions: List<CustomAccessibilityAction> = emptyList(),
    toolBackground: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    var dragged by remember { mutableStateOf(false) }
    val currentPress by rememberUpdatedState(onPress)
    val currentCancel by rememberUpdatedState(onCancel)
    val currentHide by rememberUpdatedState(onHideHint)
    val colors = LocalDocumentColors.current
    LaunchedEffect(interactions, label) {
        interactions.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> currentPress()
                is PressInteraction.Release -> currentHide(label)
                is PressInteraction.Cancel -> { currentHide(label); currentCancel() }
            }
        }
    }
    Box(
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .pointerInput(label) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    dragged = false
                    var moved = false
                    do {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            dragged = true
                            currentHide(label)
                        }
                    } while (change.pressed)
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!enabled) disabled()
                if (actions.isNotEmpty()) customActions = actions
                semanticsOnClick {
                    if (!enabled) false else {
                        dragged = false
                        onPress()
                        onClick()
                        true
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactions, indication = LocalIndication.current, role = Role.Button,
                onClick = { if (enabled && !dragged) onClick() },
                onDoubleClick = onDoubleClick?.let { action -> { if (enabled && !dragged) action() } },
                onLongClick = { if (!dragged) onShowHint(label) },
                onLongClickLabel = "显示工具名称",
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected || toolBackground) {
            Surface(Modifier.matchParentSize().padding(3.dp), shape = RoundedCornerShape(8.dp), color = if (selected) colors.soft else colors.tool) {}
        }
        val ink = (if (selected) colors.accent else colors.ink).let { if (enabled) it else it.copy(alpha = 0.38f) }
        if (label in setOf("标题", "加粗", "斜体", "删除线")) {
            Text(
                glyph,
                modifier = Modifier.clearAndSetSemantics {},
                color = ink,
                fontSize = if (label == "标题") 14.sp else 20.sp,
                fontWeight = if (label == "加粗") FontWeight.Bold else FontWeight.Medium,
                fontStyle = if (label == "斜体") FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (label == "删除线") TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 1,
            )
        } else ToolIcon(label, ink)
        if (hint == label) {
            val offset = with(LocalDensity.current) { (-40).dp.roundToPx() }
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, offset), properties = PopupProperties(focusable = false)) {
                Surface(shape = RoundedCornerShape(8.dp), color = colors.ink, shadowElevation = 4.dp) {
                    Text(label, modifier = Modifier.testTag("editor-tool-hint").padding(horizontal = 10.dp, vertical = 6.dp), color = colors.background, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// 路径使用样板的 24 × 24 逻辑画布；触摸目标仍由外层 48 dp 按钮提供。
private val toolPaths = mapOf(
    "撤销" to "M9 3 L4 8 L9 13 M4 8 H13 A6 6 0 0 1 13 20",
    "重做" to "M15 3 L20 8 L15 13 M20 8 H11 A6 6 0 0 0 11 20",
    "列表" to "M9 6 H21 M9 12 H21 M9 18 H21 M3 6 H3.1 M3 12 H3.1 M3 18 H3.1",
    "格式" to "M3 20 L9 4 L15 20 M5.2 14 H12.8 M16 10 Q21 8 21 13 V20 M21 14 Q15 13 15 17 Q15 21 21 19",
    "插入" to "M12 4 V20 M4 12 H20",
    "收起键盘" to "M5 4 H19 Q21 4 21 6 V14 Q21 16 19 16 H5 Q3 16 3 14 V6 Q3 4 5 4 M7 8 H7.1 M11 8 H11.1 M15 8 H15.1 M18 8 H18.1 M7 12 H17 M9 19 L12 21 L15 19",
    "无序列表" to "M9 6 H21 M9 12 H21 M9 18 H21 M3 6 H3.1 M3 12 H3.1 M3 18 H3.1",
    "有序列表" to "M10 6 H21 M10 12 H21 M10 18 H21 M3 3 H4 V8 M2 13 C2 10 6 10 6 12 C6 14 2 15 2 18 H6",
    "待办清单" to "M10 5 H21 M10 12 H21 M10 19 H21 M2 3 H7 V8 H2 Z M2 10 H7 V15 H2 Z M2 17 H7 V22 H2 Z",
    "勾选／取消" to "M3 12 L8 17 L20 5",
    "增加缩进" to "M3 4 H21 M11 9 H21 M11 15 H21 M3 20 H21 M3 8 L7 12 L3 16",
    "减少缩进" to "M3 4 H21 M11 9 H21 M11 15 H21 M3 20 H21 M7 8 L3 12 L7 16",
    "引用" to "M4 6 H10 V13 H5 C5 17 8 18 10 18 M14 6 H20 V13 H15 C15 17 18 18 20 18",
    "高亮" to "M5 14 L14 5 L19 10 L10 19 Z M5 14 L3 20 L10 19 M3 23 H19",
    "行内代码" to "M8 7 L3 12 L8 17 M16 7 L21 12 L16 17 M14 4 L10 20",
    "清除行内格式" to "M4 5 H16 M10 5 L7 19 M14 15 L20 21 M20 15 L14 21",
    "链接" to "M10 14 L14 10 M8 15 L6 17 A4 4 0 0 1 0 11 L4 7 A4 4 0 0 1 10 7 M12 9 L14 7 A4 4 0 0 1 20 13 L16 17 A4 4 0 0 1 10 17",
    "双链" to "M8 4 H4 V20 H8 M16 4 H20 V20 H16 M9 8 H15 M9 12 H15 M9 16 H13",
    "附件" to "M8 13 L15 6 A3 3 0 0 1 19 10 L10 19 A5 5 0 0 1 3 12 L12 3",
    "表格" to "M3 4 H21 V20 H3 Z M3 10 H21 M3 15 H21 M10 4 V20",
    "代码块" to "M4 3 H20 V21 H4 Z M9 8 L6 12 L9 16 M15 8 L18 12 L15 16",
    "分隔线" to "M3 12 H21 M6 6 H18 M6 18 H18",
    "提示块" to "M3 3 H21 V17 H9 L3 21 Z M12 6 V11 M12 14 H12.1",
    "行内公式" to "M3 9 L10 17 M10 9 L3 17 M14 7 C14 4 19 4 19 7 C19 9 14 10 14 12 H19",
    "块级公式" to "M19 4 H6 L13 12 L6 20 H19",
    "流程图" to "M9 2 H15 V7 H9 Z M2 17 H8 V22 H2 Z M16 17 H22 V22 H16 Z M12 7 V12 M5 17 V12 H19 V17",
    "脚注" to "M7 4 H4 V20 H7 M17 4 H20 V20 H17 M10 8 L12 6 V18 M10 18 H14",
)

@Composable
private fun ToolIcon(label: String, color: Color) {
    val path: Path = remember(label) { PathParser().parsePathString(toolPaths.getValue(label)).toPath() }
    Canvas(Modifier.size(22.dp).clearAndSetSemantics {}) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun HeadingLevelDialog(session: EditorSession, onDismiss: () -> Unit, onCommand: (MarkdownCommand, EditorAnchor?) -> Unit) {
    // 记录打开时的锚点；正文或选区变化后，原会话会拒绝过期命令。
    val anchor = remember(session) { session.anchor() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择标题级别") },
        text = {
            Column {
                (0..6).forEach { level ->
                    TextButton(
                        onClick = { onDismiss(); onCommand(MarkdownCommand.Heading(level), anchor) },
                        enabled = anchor != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (level == 0) "正文" else "H$level") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
