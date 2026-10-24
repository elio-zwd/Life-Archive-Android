package com.elio.lifearchive.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.elio.lifearchive.core.document.*
import java.util.concurrent.atomic.AtomicLong

enum class EditorMode(val label: String) { READ("阅读"), LIVE("实时预览 · 样板"), SOURCE("源码") }
enum class FormatAction { BOLD, ITALIC, STRIKE, HIGHLIGHT, HEADING, BULLET, ORDERED, TASK, QUOTE, CODE }

/** 只存于会话内，不把长文或过期插入请求放入 Bundle。 */
class EditorAnchor internal constructor(internal val owner: EditorSession, internal val epoch: Long, val source: String, val selection: TextRange)

/** 原文及撤销历史属于同一会话；切模式只替换展示，不重建文本状态。 */
class EditorSession(raw: String) {
    private data class EmptyPair(val style: InlineStyle, val start: Int, val length: Int, val anchor: EditorAnchor)
    private var emptyPair: EmptyPair? = null
    private val inputEpoch = AtomicLong()
    val text = TextFieldState(raw, initialSelection = TextRange(0))
    val scroll = androidx.compose.foundation.ScrollState(0)
    private val initialSource = raw
    private var contentSequence = text.text
    private var contentValue = EditorContentSnapshot(0, raw)
    private data class PendingChange(val revision: Long, val start: Int, val oldEnd: Int, val newEnd: Int)
    private var pendingChange: PendingChange? = null
    private val history = ArrayDeque<EditorTextChange>()
    private var continuationContext: ContinuationContext? = null
    private var unchangedBefore = 0
    private var displayValue: EditorDisplaySnapshot? = null
    private var newline = if (raw.contains("\r\n")) "\r\n" else "\n"
    var active by mutableStateOf(true); private set
    var focusRequest by mutableIntStateOf(0); private set
    val canModify get() = active && text.composition == null

    val content: EditorContentSnapshot get() {
        val sequence = text.text
        // TextFieldState 的公开 text 在只有选区/组合范围变化时复用正文对象。
        if (sequence !== contentSequence) {
            val source = sequence.toString()
            val before = contentValue
            val known = pendingChange?.takeIf { it.revision == before.revision && before.source.length + it.newEnd - it.oldEnd == source.length }
            val change = if (known != null) EditorTextChange(before.revision, before.revision + 1, known.start, known.oldEnd, known.newEnd)
                // 未经过会话的程序化写入不能猜测未变化范围，也不能在显示回调中差分全文。
                else EditorTextChange(before.revision, before.revision + 1, 0, before.source.length, source.length)
            if (newline == "\n") {
                val end = minOf(source.length - 1, change.newEnd)
                for (i in (change.start - 1).coerceAtLeast(0) until end) if (source[i] == '\r' && source[i + 1] == '\n') { newline = "\r\n"; break }
            }
            unchangedBefore = minOf(unchangedBefore, change.start)
            history += change
            if (history.size > 256) history.removeFirst()
            contentValue = EditorContentSnapshot(change.after, source)
            contentSequence = sequence; pendingChange = null
        }
        return contentValue
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun rememberChange(buffer: TextFieldBuffer, before: EditorContentSnapshot) {
        if (buffer.changes.changeCount == 0) return
        var start = buffer.originalText.length; var end = 0
        repeat(buffer.changes.changeCount) { val range = buffer.changes.getOriginalRange(it); start = minOf(start, range.min); end = maxOf(end, range.max) }
        var newEnd = end + buffer.length - buffer.originalText.length
        val current = buffer.asCharSequence()
        // 续写为隔离撤销而替换了相邻字符，但该字符的样式和标记并没有变化。
        while (start < minOf(end, newEnd) && buffer.originalText[start] == current[start]) start++
        while (end > start && newEnd > start && buffer.originalText[end - 1] == current[newEnd - 1]) { end--; newEnd-- }
        pendingChange = PendingChange(before.revision, start, end, newEnd)
        inputEpoch.incrementAndGet()
    }

    fun edit(block: TextFieldBuffer.() -> Unit) {
        val before = content
        text.edit { block(); rememberChange(this, before) }
        content
    }

    internal fun installContext(snapshot: EditorContentSnapshot, context: ContinuationContext): Boolean {
        if (!active || content.revision != snapshot.revision) return false
        continuationContext = context; unchangedBefore = snapshot.source.length
        return true
    }

    internal fun installDisplay(snapshot: EditorContentSnapshot, display: EditorDisplaySnapshot): Boolean {
        if (!active || content.revision != snapshot.revision) return false
        displayValue = display
        return true
    }

    internal fun display(parsed: ParsedDocument): EditorDisplaySnapshot {
        displayValue?.takeIf { it.document === parsed }?.let { return it }
        val current = content
        val baseRevision = when (parsed.source) { current.source -> current.revision; initialSource -> 0; else -> -1 }
        // 无 ViewModel 的预览/测试宿主也能使用同一引擎；正式页面在后台准备索引。
        val prepared = if (baseRevision >= 0) EditorDisplaySnapshot(baseRevision, parsed)
            else EditorDisplaySnapshot(current.revision, EditorRenderProjection(current.revision, parsed).forText(current.source))
        displayValue = prepared
        return prepared
    }

    internal fun changesSince(revision: Long): List<EditorTextChange>? {
        val current = content
        if (current.revision == revision) return emptyList()
        if (history.firstOrNull()?.before?.let { it > revision } != false) return null
        val changes = history.filter { it.before >= revision }
        return changes.takeIf { it.firstOrNull()?.before == revision && it.lastOrNull()?.after == current.revision }
    }

    fun anchor(): EditorAnchor? = if (canModify) EditorAnchor(this, inputEpoch.get(), content.source, text.selection) else null
    fun matches(anchor: EditorAnchor): Boolean = canModify && anchor.owner === this && anchor.epoch == inputEpoch.get() && anchor.selection == text.selection && anchor.source == content.source
    fun close() { active = false; continuationContext = null; displayValue = null; history.clear() }
    fun requestInputFocus() { if (active) focusRequest++ }

    fun apply(anchor: EditorAnchor, edit: MarkdownEdit): String? {
        if (!matches(anchor)) return "正文或选区已变化，请重新操作"
        edit.error?.let { return it }
        if (edit.edits.isEmpty()) return null
        inputEpoch.incrementAndGet()
        edit {
            edit.edits.sortedBy { it.start }.asReversed().forEach { replace(it.start, it.end, it.replacement) }
            selection = TextRange(edit.selection.start, edit.selection.end)
        }
        val inserted = edit.edits.singleOrNull()?.takeIf { anchor.selection.collapsed && it.start == it.end }
        val style = when (inserted?.replacement) { "****" -> InlineStyle.BOLD; "**" -> InlineStyle.ITALIC; "~~~~" -> InlineStyle.STRIKE; "====" -> InlineStyle.HIGHLIGHT; "``" -> InlineStyle.CODE; else -> null }
        emptyPair = if (style != null && inserted != null) EmptyPair(style, inserted.start, inserted.replacement.length, this.anchor()!!) else null
        return null
    }

    fun calculate(anchor: EditorAnchor, command: MarkdownCommand): MarkdownEdit {
        // 空删除线与围栏可能同形；只取消本会话刚创建且未被改动的成对占位，不猜测用户原有代码。
        val pair = emptyPair
        if (command is MarkdownCommand.Inline && pair?.style == command.style && pair.anchor.epoch == anchor.epoch && pair.anchor.source == anchor.source && pair.anchor.selection == anchor.selection)
            return MarkdownEdit(listOf(SourceEdit(pair.start, pair.start + pair.length, "")), EditSelection(pair.start))
        return MarkdownEditing.calculate(anchor.source, EditSelection(anchor.selection.start, anchor.selection.end), command)
    }

    fun command(command: MarkdownCommand, anchor: EditorAnchor? = anchor()): String? {
        anchor ?: return "请先确认输入中的文字"
        return apply(anchor, calculate(anchor, command))
    }

    @OptIn(ExperimentalFoundationApi::class)
    val continuation = InputTransformation {
        val snapshot = content
        try { continueNewline() } finally { rememberChange(this, snapshot) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun TextFieldBuffer.continueNewline() {
        // 只改这一次输入缓冲区，不调用 text.edit，也不在观察流中追加第二次写入。
        if (!active || text.composition != null || !originalSelection.collapsed || changes.changeCount != 1) return
        val original = changes.getOriginalRange(0); val changed = changes.getRange(0)
        if (!original.collapsed || changed.length != 1 || asCharSequence()[changed.min] != '\n') return
        val before = originalText
        val next = try {
            MarkdownContinuation.calculate(before, original.min, continuationContext, unchangedBefore, newline)
        } catch (_: IllegalArgumentException) { null } catch (_: IndexOutOfBoundsException) { null }
        if (next != null && next.isValid(before.length, original.min)) {
            // 保留相邻完整字符，形成独立替换，避免原生历史将前一个候选词提交合并进续写撤销。
            var start = next.start
            if (start == next.end && start > 0) {
                start--
                if (start > 0 && before[start].isLowSurrogate() && before[start - 1].isHighSurrogate()) start--
            }
            replace(start, next.end + 1, before.subSequence(start, next.start).toString() + next.replacement)
            selection = TextRange(next.cursor)
        } else if (newline == "\r\n") {
            replace(changed.min, changed.max, newline); selection = TextRange(changed.min + newline.length)
        }
    }

    fun format(action: FormatAction) {
        command(when (action) {
            FormatAction.BOLD -> MarkdownCommand.Inline(InlineStyle.BOLD)
            FormatAction.ITALIC -> MarkdownCommand.Inline(InlineStyle.ITALIC)
            FormatAction.STRIKE -> MarkdownCommand.Inline(InlineStyle.STRIKE)
            FormatAction.HIGHLIGHT -> MarkdownCommand.Inline(InlineStyle.HIGHLIGHT)
            FormatAction.CODE -> MarkdownCommand.Inline(InlineStyle.CODE)
            FormatAction.HEADING -> MarkdownCommand.AdjustHeading(1)
            FormatAction.BULLET -> MarkdownCommand.List(ListStyle.BULLET)
            FormatAction.ORDERED -> MarkdownCommand.List(ListStyle.ORDERED)
            FormatAction.TASK -> MarkdownCommand.List(ListStyle.TASK)
            FormatAction.QUOTE -> MarkdownCommand.Quote
        })
    }

    fun insert(textToInsert: String) {
        if (!canModify) return
        inputEpoch.incrementAndGet()
        edit {
            val start = selection.min
            val inserted = textToInsert.replace("\r\n", "\n").replace("\n", newline)
            replace(start, selection.max, inserted)
            selection = TextRange(start + inserted.length)
        }
    }

    private fun historyChanged(before: EditorContentSnapshot) {
        val source = text.text.toString()
        var start = 0; var suffix = 0
        while (start < minOf(before.source.length, source.length) && before.source[start] == source[start]) start++
        while (suffix < minOf(before.source.length, source.length) - start && before.source[before.source.lastIndex - suffix] == source[source.lastIndex - suffix]) suffix++
        // 公开撤销接口不提供 ChangeList；差分仅在显式历史动作中计算一次。
        pendingChange = PendingChange(before.revision, start, before.source.length - suffix, source.length - suffix)
        content
    }

    @OptIn(ExperimentalFoundationApi::class)
    fun undo() { if (canModify && text.undoState.canUndo) { val before = content; inputEpoch.incrementAndGet(); text.undoState.undo(); historyChanged(before) } }
    @OptIn(ExperimentalFoundationApi::class)
    fun redo() { if (canModify && text.undoState.canRedo) { val before = content; inputEpoch.incrementAndGet(); text.undoState.redo(); historyChanged(before) } }
}
