package com.elio.lifearchive.feature.editor

import androidx.compose.ui.text.TextRange
import com.elio.lifearchive.core.document.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class EditorCommandSessionTest {
    @Test fun commandsAreOneUndoStepAndDoNotEraseOlderHistory() {
        val session = EditorSession("原文")
        session.insert("前缀")
        val before = session.text.text.toString()
        session.text.edit { selection = TextRange(0, length) }
        assertNull(session.command(MarkdownCommand.Inline(InlineStyle.BOLD)))
        assertEquals("**$before**", session.text.text.toString())
        session.undo(); assertEquals(before, session.text.text.toString())
        session.undo(); assertEquals("原文", session.text.text.toString())
        session.redo(); session.redo(); assertEquals("**$before**", session.text.text.toString())
    }

    @Test fun splitFormattingAndFootnotesUndoWithoutLeavingHalfATransaction() {
        val session = EditorSession("**甲乙丙**")
        session.text.edit { selection = TextRange(3, 4) }
        session.command(MarkdownCommand.Inline(InlineStyle.BOLD))
        assertEquals("**甲**乙**丙**", session.text.text.toString())
        session.undo(); assertEquals("**甲乙丙**", session.text.text.toString())
        session.text.edit { selection = TextRange(2, 5) }
        session.command(MarkdownCommand.Template(InsertTemplate.FOOTNOTE))
        assertTrue(session.text.text.toString().contains("[^fn1]: 甲乙丙"))
        session.undo(); assertEquals("**甲乙丙**", session.text.text.toString())
    }

    @Test fun emptyInlinePairsCancelWithoutMistakingOriginalCodeForPlaceholders() {
        InlineStyle.entries.forEach { style ->
            val session = EditorSession("")
            assertNull(session.command(MarkdownCommand.Inline(style)))
            assertNull(session.command(MarkdownCommand.Inline(style)))
            assertEquals(style.name, "", session.text.text.toString())
            session.undo(); assertTrue(session.text.text.isNotEmpty())
        }
        val originalCode = EditorSession("~~~~\n- 不应修改\n~~~~")
        originalCode.text.edit { selection = TextRange(2) }
        assertNotNull(originalCode.command(MarkdownCommand.Inline(InlineStyle.STRIKE)))
    }

    @Test fun footnoteInAnEmptyDocumentKeepsReferenceBeforeTheDefinition() {
        val session = EditorSession("")
        session.command(MarkdownCommand.Template(InsertTemplate.FOOTNOTE))
        assertEquals("[^fn1]\n\n[^fn1]: 脚注内容\n", session.text.text.toString())
        assertTrue(HtmlProjection.render(session.text.text.toString()).contains("<section class=\"footnotes\""))
        session.undo(); assertEquals("", session.text.text.toString())
    }

    @Test fun staleSourceSelectionOtherSessionAndClosedAnchorsCannotWrite() {
        val session = EditorSession("甲乙")
        val old = session.anchor()!!
        session.text.edit { append("新输入") }
        assertNotNull(session.command(MarkdownCommand.Heading(1), old))
        assertEquals("甲乙新输入", session.text.text.toString())
        val selected = session.anchor()!!
        session.text.edit { selection = TextRange(1) }
        assertNotNull(session.command(MarkdownCommand.Heading(1), selected))
        assertNotNull(EditorSession("甲乙").command(MarkdownCommand.Heading(1), old))
        val last = session.anchor()!!; session.close()
        assertNotNull(session.command(MarkdownCommand.Heading(1), last))
    }

    @Test fun typingThenUndoDoesNotReviveAnOldInsertionRequest() {
        val session = EditorSession("原文")
        val old = session.anchor()!!
        session.insert("输入"); session.undo()
        assertEquals("原文", session.text.text.toString())
        assertNotNull(session.command(MarkdownCommand.Heading(1), old))
        assertEquals("原文", session.text.text.toString())
    }

    @Test fun boundaryHeadingNoOpsDoNotCreateUndoEntriesAndHighlightKeepsProtectedMarkers() {
        val session = EditorSession("###### 六级")
        assertNull(session.command(MarkdownCommand.AdjustHeading(1)))
        session.undo(); assertEquals("###### 六级", session.text.text.toString())
        val raw = "==高亮==\n\n`==代码==`\n\n---\n"
        val parsed = MarkdownParser().parse(raw)
        assertEquals(1, parsed.styles.count { it.mark == TextMark.HIGHLIGHT })
        assertTrue(visibleMarkers(parsed, TextRange(3), null).none { it.start < 6 })
        assertEquals(raw, parsed.source)
    }

    @Test fun longDocumentEditsPreserveUnrelatedBytesAndExistingNewlines() {
        for (bytes in listOf(50 * 1024, 1024 * 1024)) {
            val raw = "\uFEFF---\r\nunknown: &x value\r\ncopy: *x\r\n---\r\n" + "正文 **保留**\r\n\r\n".repeat(bytes / 12)
            val session = EditorSession(raw)
            val at = raw.indexOf("正文")
            session.text.edit { selection = TextRange(at) }
            session.command(MarkdownCommand.Heading(1))
            assertEquals(raw.substring(0, at) + "# " + raw.substring(at), session.text.text.toString())
            session.undo(); assertEquals(raw, session.text.text.toString())
            session.insert("第一行\r\n第二行")
            assertFalse(session.text.text.contains("\r\r\n"))
        }
    }
}
