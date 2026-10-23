package com.elio.lifearchive.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    private val newline = if (raw.contains("\r\n")) "\r\n" else "\n"
    var active by mutableStateOf(true); private set
    var focusRequest by mutableStateOf(0); private set
    val canModify get() = active && text.composition == null

    fun anchor(): EditorAnchor? = if (canModify) EditorAnchor(this, inputEpoch.get(), text.text.toString(), text.selection) else null
    fun matches(anchor: EditorAnchor): Boolean = canModify && anchor.owner === this && anchor.epoch == inputEpoch.get() && anchor.selection == text.selection && anchor.source.contentEquals(text.text)
    fun close() { active = false }
    fun requestInputFocus() { if (active) focusRequest++ }

    fun apply(anchor: EditorAnchor, edit: MarkdownEdit): String? {
        if (!matches(anchor)) return "正文或选区已变化，请重新操作"
        edit.error?.let { return it }
        if (edit.edits.isEmpty()) return null
        inputEpoch.incrementAndGet()
        text.edit {
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
        if (changes.changeCount > 0) inputEpoch.incrementAndGet()
        // 只改这一次输入缓冲区，不调用 text.edit，也不在观察流中追加第二次写入。
        if (!active || text.composition != null || !originalSelection.collapsed || changes.changeCount != 1) return@InputTransformation
        val original = changes.getOriginalRange(0); val changed = changes.getRange(0)
        if (!original.collapsed || changed.length != 1 || asCharSequence()[changed.min] != '\n') return@InputTransformation
        val before = originalText.toString()
        val next = MarkdownContinuation.calculate(before, original.min)
        if (next != null) {
            // 保留相邻完整字符，形成独立替换，避免原生历史将前一个候选词提交合并进续写撤销。
            var start = next.start
            if (start == next.end && start > 0) {
                start--
                if (start > 0 && before[start].isLowSurrogate() && before[start - 1].isHighSurrogate()) start--
            }
            replace(start, next.end + 1, before.substring(start, next.start) + next.replacement)
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
        text.edit {
            val start = selection.min
            val inserted = textToInsert.replace("\r\n", "\n").replace("\n", newline)
            replace(start, selection.max, inserted)
            selection = TextRange(start + inserted.length)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    fun undo() { if (canModify) { inputEpoch.incrementAndGet(); text.undoState.undo() } }
    @OptIn(ExperimentalFoundationApi::class)
    fun redo() { if (canModify) { inputEpoch.incrementAndGet(); text.undoState.redo() } }
}
