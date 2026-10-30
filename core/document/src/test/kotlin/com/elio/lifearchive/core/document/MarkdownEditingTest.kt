package com.elio.lifearchive.core.document

import org.junit.Assert.*
import org.junit.Test

class MarkdownEditingTest {
    private fun edit(raw: String, command: MarkdownCommand, start: Int = 0, end: Int = start): Pair<String, MarkdownEdit> {
        val result = MarkdownEditing.calculate(raw, EditSelection(start, end), command)
        assertNull(result.error)
        return result.apply(raw) to result
    }

    @Test fun headingStepsStopAtBothBoundsAndNeverStackMarkers() {
        var text = "正文"
        repeat(8) { text = edit(text, MarkdownCommand.AdjustHeading(1)).first }
        assertEquals("###### 正文", text)
        repeat(8) { text = edit(text, MarkdownCommand.AdjustHeading(-1)).first }
        assertEquals("正文", text)
        assertTrue(edit(text, MarkdownCommand.AdjustHeading(-1)).second.edits.isEmpty())
    }

    @Test fun headingChangesPreserveContainersClosingMarkersAndSetextFollowingText() {
        assertEquals("> - ## **标题**", edit("> - # **标题** ###", MarkdownCommand.Heading(2)).first)
        assertEquals("## 标题\r\n后文", edit("标题\r\n===\r\n后文", MarkdownCommand.AdjustHeading(1)).first)
        assertEquals("标题\n后文", edit("标题\n---\n后文", MarkdownCommand.Heading(0)).first)
    }

    @Test fun multipleLinesUseRelativeLevelsAndExcludeTheNextLineStart() {
        val raw = "# 甲\n\n## 乙\n丙"
        val end = raw.indexOf('丙')
        val (text, selection) = edit(raw, MarkdownCommand.AdjustHeading(1), end, 0)
        assertEquals("## 甲\n\n### 乙\n丙", text)
        assertTrue(selection.selection.start > selection.selection.end)
        assertEquals(null, MarkdownEditing.tools(raw, EditSelection(0, end)).heading)
        assertEquals("# ", edit("", MarkdownCommand.AdjustHeading(1)).first)
    }

    @Test fun lineCommandsPreserveBomYamlCrLfAndOtherBytes() {
        val raw = "\uFEFF---\r\nunknown: &anchor yes\r\ncopy: *anchor\r\n---\r\n甲\r\n乙\r\n"
        val (changed, _) = edit(raw, MarkdownCommand.List(ListStyle.BULLET), raw.indexOf('甲'), raw.indexOf('乙') + 1)
        assertEquals(raw.replace("甲", "- 甲").replace("乙", "- 乙"), changed)
    }

    @Test fun setextUnderlineUsesItsHeadingAndIsNotFormattedAsBody() {
        val raw = "标题\n===\n后文"
        assertEquals(1, MarkdownEditing.tools(raw, EditSelection(4)).heading)
        assertEquals("## 标题\n后文", edit(raw, MarkdownCommand.AdjustHeading(1), 4).first)
        assertEquals("**标题**\n===\n**后文**", edit(raw, MarkdownCommand.Inline(InlineStyle.BOLD), 0, raw.length).first)
        assertEquals("# **标题**\n- **列表**", edit("# 标题\n- 列表", MarkdownCommand.Inline(InlineStyle.BOLD), 0, 9).first)
        assertEquals("# **标题** ###", edit("# 标题 ###", MarkdownCommand.Inline(InlineStyle.BOLD), 0, 8).first)
    }

    @Test fun multilineSetextConvertsTheWholeParsedHeadingOnce() {
        val raw = "> 第一行\r\n> **第二行**\r\n> ===\r\n后文"
        assertEquals(1, MarkdownEditing.tools(raw, EditSelection(3), MarkdownParser().parse(raw)).heading)
        val changed = edit(raw, MarkdownCommand.AdjustHeading(1), 3).first
        assertEquals("> ## 第一行 **第二行**\r\n后文", changed)
        assertEquals("> # 第一行 **第二行**\r\n后文", edit(raw, MarkdownCommand.Heading(1), raw.indexOf("==")).first)
        assertEquals("*甲*", edit("***甲***", MarkdownCommand.Inline(InlineStyle.BOLD), 3).first)
        assertEquals("**甲**", edit("***甲***", MarkdownCommand.Inline(InlineStyle.ITALIC), 3).first)
    }

    @Test fun listsConvertToggleAndKeepTaskStatesAndOuterQuotes() {
        val raw = "> - 甲\n> - [x] 乙"
        val tasks = edit(raw, MarkdownCommand.List(ListStyle.TASK), 0, raw.length).first
        assertEquals("> - [ ] 甲\n> - [x] 乙", tasks)
        assertEquals("> 甲\n> 乙", edit(tasks, MarkdownCommand.List(ListStyle.TASK), 0, tasks.length).first)
        assertEquals("> 1. 甲\n> 2. 乙", edit(raw, MarkdownCommand.List(ListStyle.ORDERED), 0, raw.length).first)
        val checked = edit(tasks, MarkdownCommand.ToggleTask, 0, tasks.length).first
        assertEquals("> - [x] 甲\n> - [x] 乙", checked)
        assertEquals("> - [ ] 甲\n> - [ ] 乙", edit(checked, MarkdownCommand.ToggleTask, 0, checked.length).first)
    }

    @Test fun listIndentAndQuoteAreReversibleAndDoNotTouchPlainText() {
        val raw = "> - 父\n> - 甲\n> - 乙"
        val indented = edit(raw, MarkdownCommand.Indent(1), raw.indexOf("> - 甲"), raw.length).first
        assertEquals("> - 父\n>     - 甲\n>     - 乙", indented)
        assertEquals(raw, edit(indented, MarkdownCommand.Indent(-1), indented.indexOf(">     - 甲"), indented.length).first)
        assertNotNull(MarkdownEditing.calculate(raw, EditSelection(0), MarkdownCommand.Indent(1)).error)
        assertEquals("甲", edit("甲", MarkdownCommand.Indent(1)).first)
        assertEquals("父\n甲\n乙", edit(raw, MarkdownCommand.List(ListStyle.BULLET), 0, raw.length).first.let { edit(it, MarkdownCommand.Quote, 0, it.length).first })
    }

    @Test fun inlineFormatKeepsUnicodeSelectionAndCanToggleBack() {
        val raw = "前面 中文🙂 后面"
        val end = raw.indexOf(" 后面")
        val (bold, result) = edit(raw, MarkdownCommand.Inline(InlineStyle.BOLD), 3, end)
        assertEquals("前面 **中文🙂** 后面", bold)
        assertEquals("中文🙂", bold.substring(result.selection.min, result.selection.max))
        assertEquals(raw, edit(bold, MarkdownCommand.Inline(InlineStyle.BOLD), result.selection.start, result.selection.end).first)
        val (empty, caret) = edit("", MarkdownCommand.Inline(InlineStyle.ITALIC))
        assertEquals("**", empty); assertEquals(EditSelection(1), caret.selection)
    }

    @Test fun clearInlineRemovesCompleteSimpleMarksAndKeepsReversedSelection() {
        val raw = "前 **甲🙂** *乙* ~~丙~~ ==丁== 后"
        val start = raw.indexOf("**"); val end = raw.indexOf(" 后")
        val (changed, result) = edit(raw, MarkdownCommand.ClearInline, end, start)
        assertEquals("前 甲🙂 乙 丙 丁 后", changed)
        assertEquals("甲🙂 乙 丙 丁", changed.substring(result.selection.min, result.selection.max))
        assertTrue(result.selection.start > result.selection.end)
    }

    @Test fun clearInlineRecognizesCompleteTextAdjacentToTheSameOuterMarks() {
        val raw = "前 **甲乙** 后"
        val (changed, result) = edit(raw, MarkdownCommand.ClearInline, raw.indexOf('甲'), raw.indexOf('乙') + 1)
        assertEquals("前 甲乙 后", changed)
        assertEquals("甲乙", changed.substring(result.selection.min, result.selection.max))
    }

    @Test fun clearInlineRejectsPartialNestedCodeAndMultilineSelectionsWithoutChangingSource() {
        val cases = listOf(
            Triple("**甲乙丙**", 3, 4),
            Triple("**甲*乙*丙**", 0, 9),
            Triple("`代码`", 0, 4),
            Triple("**甲**\n*乙*", 0, 9),
            Triple("[**甲**](https://example.com)", 1, 6),
            Triple("**甲**", 2, 2),
        )
        cases.forEach { (raw, start, end) ->
            val change = MarkdownEditing.calculate(raw, EditSelection(start, end), MarkdownCommand.ClearInline)
            assertNotNull(raw, change.error)
            assertEquals(raw, change.apply(raw))
        }
    }

    @Test fun clearInlineCannotCreateBlockSyntaxOrChangeAnAdjacentDelimiter() {
        val cases = listOf(
            "**# 标题**", "**- 列表**", "**1. 列表**", "**> 引用**", "**---**", "段落\n**---**",
        )
        cases.forEach { raw ->
            val start = raw.indexOf("**")
            val change = MarkdownEditing.calculate(raw, EditSelection(start, raw.length), MarkdownCommand.ClearInline)
            assertNotNull(raw, change.error)
            assertEquals(raw, change.apply(raw))
        }
        val adjacent = "_甲_**乙**"
        val change = MarkdownEditing.calculate(adjacent, EditSelection(adjacent.indexOf("**"), adjacent.length), MarkdownCommand.ClearInline)
        assertNotNull(change.error)
        assertEquals(adjacent, change.apply(adjacent))
    }

    @Test fun clearInlinePreservesExistingHeadingAndListContainers() {
        for (raw in listOf("# **标题**", "- **条目**")) {
            val start = raw.indexOf("**")
            val change = MarkdownEditing.calculate(raw, EditSelection(start, raw.length), MarkdownCommand.ClearInline)
            assertNull(raw, change.error)
            assertEquals(raw.replace("**", ""), change.apply(raw))
        }
    }

    @Test fun partialRemovalLeavesFormattingOutsideTheSelection() {
        val raw = "**甲乙丙**"
        val (changed, result) = edit(raw, MarkdownCommand.Inline(InlineStyle.BOLD), 3, 4)
        assertEquals("**甲**乙**丙**", changed)
        assertEquals("乙", changed.substring(result.selection.min, result.selection.max))
        val html = HtmlProjection.render(changed)
        assertTrue(html.contains(">甲</strong>乙<strong")); assertTrue(html.contains(">丙</strong>"))
    }

    @Test fun nestedStrongAndEmphasisKeepTheOtherStyle() {
        assertEquals("甲*乙*丙", edit("**甲*乙*丙**", MarkdownCommand.Inline(InlineStyle.BOLD), 5).first)
        val (changed, _) = edit("*甲乙丙*", MarkdownCommand.Inline(InlineStyle.BOLD), 2, 3)
        val html = HtmlProjection.render(changed)
        assertTrue(html.contains("<em")); assertTrue(html.contains(">乙</strong>"))
    }

    @Test fun multilineFormatsSkipEmptyLinesAndUseSafeCodeDelimiters() {
        val raw = "甲\r\n\r\n乙"
        assertEquals("**甲**\r\n\r\n**乙**", edit(raw, MarkdownCommand.Inline(InlineStyle.BOLD), 0, raw.length).first)
        val code = edit("a`b", MarkdownCommand.Inline(InlineStyle.CODE), 0, 3).first
        assertEquals("``a`b``", code)
        assertTrue(HtmlProjection.render(code).contains("a`b</code>"))
        assertEquals("`x", edit("`` `x ``", MarkdownCommand.Inline(InlineStyle.CODE), 4).first)
    }

    @Test fun protectedStructuresRejectCommandsWithoutMutatingAnything() {
        val samples = listOf("---\nunknown: yes\n---\n" to 7, "```kotlin\n- literal\n```" to 13, "<div>\ntext\n</div>" to 8, "| 甲 |\n| --- |\n| 乙 |" to 17)
        samples.forEach { (raw, at) ->
            val result = MarkdownEditing.calculate(raw, EditSelection(at), MarkdownCommand.AdjustHeading(1))
            assertNotNull(raw, result.error); assertEquals(raw, result.apply(raw))
        }
    }

    @Test fun allTemplatesProduceReaderContentAndLeaveAnEditableSelection() {
        InsertTemplate.entries.forEach { kind ->
            val (raw, change) = edit("", MarkdownCommand.Template(kind))
            assertTrue(kind.name, raw.isNotBlank()); assertTrue(change.selection.max <= raw.length)
            val html = HtmlProjection.render(raw)
            val expected = when (kind) {
                InsertTemplate.TABLE -> "<table"
                InsertTemplate.CODE_BLOCK -> "<pre"
                InsertTemplate.DIVIDER -> "<hr"
                InsertTemplate.CALLOUT -> "[!NOTE]"
                InsertTemplate.INLINE_MATH, InsertTemplate.BLOCK_MATH -> "data-tex"
                InsertTemplate.MERMAID -> "language-mermaid"
                InsertTemplate.FOOTNOTE -> "footnote"
            }
            assertTrue("$kind: $html", html.contains(expected))
        }
    }

    @Test fun footnoteAllocationAndCodeFencesPreserveSelectedSource() {
        val raw = "正文[^fn1]\r\n\r\n[^fn1]: 原文\r\n"
        val changed = edit(raw, MarkdownCommand.Template(InsertTemplate.FOOTNOTE), 0, 2).first
        assertTrue(changed.startsWith("[^fn2][^fn1]")); assertTrue(changed.contains("[^fn2]: 正文")); assertTrue(changed.contains("[^fn1]: 原文"))
        val selected = "val x = \"```\""
        val code = edit(selected, MarkdownCommand.Template(InsertTemplate.CODE_BLOCK, "kotlin"), 0, selected.length).first
        assertTrue(code.startsWith("````kotlin\n")); assertTrue(code.contains(selected))
    }

    @Test fun selectedTextSurvivesDividerTableAndMultilineCalloutInsertion() {
        val selected = "第一|列\r\n第二行"
        val divider = edit(selected, MarkdownCommand.Template(InsertTemplate.DIVIDER), 0, selected.length).first
        assertEquals("---\r\n\r\n$selected\r\n", divider)
        val table = edit(selected, MarkdownCommand.Template(InsertTemplate.TABLE), 0, selected.length).first
        assertTrue(table.contains("第一\\|列<br>第二行"))
        assertTrue(HtmlProjection.render(table).contains("第一|列<br"))
        val (callout, change) = edit(selected, MarkdownCommand.Template(InsertTemplate.CALLOUT, "TIP"), 0, selected.length)
        assertEquals("> [!TIP] 提示\r\n> 第一|列\r\n> 第二行\r\n", callout)
        assertEquals("第一|列\r\n> 第二行", callout.substring(change.selection.min, change.selection.max))
        assertNotNull(MarkdownEditing.calculate("`代码`", EditSelection(2), MarkdownCommand.Template(InsertTemplate.INLINE_MATH)).error)
    }

    @Test fun referencesEscapeLabelsUseRelativePathsAndRejectAmbiguousWikiNames() {
        assertEquals("[显示\\[名\\]](https://example.com/a%28b%29)", MarkdownReferences.external("显示[名]", "https://example.com/a(b)"))
        assertEquals("[目标](../%E8%B5%84%E6%96%99/a%23b%28c%29.md)", MarkdownReferences.relative(LibraryPath("日记/本篇.md"), LibraryPath("资料/a#b(c).md"), "目标"))
        assertNull(MarkdownReferences.wiki(LibraryPath("a#b.md")))
        assertEquals("[[目录/笔记.md|显示名]]", MarkdownReferences.wiki(LibraryPath("目录/笔记.md"), "显示名"))
        assertThrows(IllegalArgumentException::class.java) { MarkdownReferences.external("危险", "javascript:alert(1)") }
    }

    @Test fun continuationNumbersTasksQuotesAndMidLineText() {
        fun continued(raw: String, at: Int = raw.length): String {
            val change = MarkdownContinuation.calculate(raw, at) ?: error(raw)
            return raw.replaceRange(change.start, change.end, change.replacement)
        }
        assertEquals("- 甲\n- ", continued("- 甲"))
        assertEquals("9. [x] 甲\n10. [ ] ", continued("9. [x] 甲"))
        assertEquals("> - [X] 甲\n> - [ ] ", continued("> - [X] 甲"))
        assertEquals("> > 引用\n> > ", continued("> > 引用"))
        assertEquals("- 甲\n- 乙", continued("- 甲乙", 3))
        assertEquals("- 父\n    - 子\n    - ", continued("- 父\n    - 子"))
    }

    @Test fun emptyContinuationExitsOneLevelWithoutAddingExtraItems() {
        fun exit(raw: String): String {
            val change = MarkdownContinuation.calculate(raw, raw.length) ?: error(raw)
            return raw.replaceRange(change.start, change.end, change.replacement)
        }
        assertEquals("", exit("- ")); assertEquals("> ", exit("> > "))
        assertEquals("- 父\n- ", exit("- 父\n    - "))
        assertEquals("> ", exit("> - [ ] "))
    }

    @Test fun continuationDoesNotTreatProtectedSourceAsAList() {
        listOf("---\n- yaml", "```\n- code", "~~~\n- code", "    - code", ">     - code", "<div>\n- html", "> | 甲 |\n> | --- |\n> | - cell |").forEach { raw -> assertNull(raw, MarkdownContinuation.calculate(raw, raw.length)) }
        assertNull(MarkdownContinuation.calculate("# 标题", 4))
    }
}
