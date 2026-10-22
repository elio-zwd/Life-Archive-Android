package com.elio.lifearchive.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

enum class EditorMode(val label: String) { READ("阅读"), LIVE("实时预览 · 样板"), SOURCE("源码") }
enum class FormatAction { BOLD, ITALIC, HEADING, BULLET, TASK, QUOTE, CODE }

/** 原文及撤销历史属于同一会话；切模式只替换展示，不重建文本状态。 */
class EditorSession(raw: String) {
    val text = TextFieldState(raw, initialSelection = TextRange(0))
    private val newline = if (raw.contains("\r\n")) "\r\n" else "\n"

    fun format(action: FormatAction) {
        // 避免工具栏操作中途提交或重写输入法组合文本。
        if (text.composition != null) return
        text.edit {
            val start = selection.min
            val end = selection.max
            when (action) {
                FormatAction.BOLD, FormatAction.ITALIC, FormatAction.CODE -> {
                    val token = when (action) { FormatAction.BOLD -> "**"; FormatAction.ITALIC -> "*"; else -> "`" }
                    replace(end, end, token)
                    replace(start, start, token)
                    selection = TextRange(start + token.length, end + token.length)
                }
                else -> {
                    val content = asCharSequence().toString()
                    val lineStart = content.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)).let { it + 1 }
                    val prefix = when (action) {
                        FormatAction.HEADING -> "## "
                        FormatAction.BULLET -> "- "
                        FormatAction.TASK -> "- [ ] "
                        else -> "> "
                    }
                    val starts = mutableListOf(lineStart)
                    var next = content.indexOf('\n', lineStart)
                    while (next >= 0 && next + 1 < end) { starts += next + 1; next = content.indexOf('\n', next + 1) }
                    starts.asReversed().forEach { replace(it, it, prefix) }
                    selection = TextRange(start + prefix.length, end + prefix.length * starts.size)
                }
            }
        }
    }

    fun insert(textToInsert: String) {
        if (text.composition != null) return
        text.edit {
            val start = selection.min
            val inserted = textToInsert.replace("\n", newline)
            replace(start, selection.max, inserted)
            selection = TextRange(start + inserted.length)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    fun undo() { text.undoState.undo() }
    @OptIn(ExperimentalFoundationApi::class)
    fun redo() { text.undoState.redo() }
}
