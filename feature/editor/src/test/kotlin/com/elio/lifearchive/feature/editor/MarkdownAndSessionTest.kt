package com.elio.lifearchive.feature.editor

import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MarkdownAndSessionTest {
    @Test fun sampleHasTablesCalloutsImagePropertiesAndRawComplexBlocks() {
        val original = PrototypeContent.document()
        val parsed = MarkdownParser().parse(original)
        for (kind in listOf(BlockKind.TABLE, BlockKind.CALLOUT, BlockKind.IMAGE, BlockKind.PROPERTIES, BlockKind.RAW)) assertTrue("缺少 $kind", parsed.blocks.any { it.kind == kind })
        assertEquals(original, parsed.source)
        parsed.blocks.forEach { assertTrue(it.start >= 0 && it.end <= original.length && it.start <= it.end) }
        assertTrue(parsed.styles.any { it.mark == TextMark.BOLD })
    }

    @Test fun activeAndComposingLinesKeepSyntaxMarkers() {
        val parsed = MarkdownParser().parse("# 标题\n\n**中文**\n\n*末尾*")
        val hidden = visibleMarkers(parsed, TextRange(2), TextRange(8, 10))
        assertTrue(hidden.all { it.start >= 14 })
        assertFalse(hidden.isEmpty())
    }

    @Test fun formattingPreservesSelectionAndUndoRedoAcrossModes() {
        val session = EditorSession("前面 中文 后面")
        session.text.edit { selection = TextRange(3, 5) }
        session.format(FormatAction.BOLD)
        assertEquals("前面 **中文** 后面", session.text.text.toString())
        assertEquals(TextRange(5, 7), session.text.selection)
        session.undo()
        assertEquals("前面 中文 后面", session.text.text.toString())
        session.redo()
        assertEquals("前面 **中文** 后面", session.text.text.toString())
        EditorMode.entries.forEach { MarkdownParser().parse(session.text.text.toString()) }
        assertEquals("前面 **中文** 后面", session.text.text.toString())
    }

    @Test fun multilineFormattingRetainsCrLfAndUnknownYamlUnchanged() {
        val original = "\uFEFF---\r\nunknown: yes\r\n---\r\n甲\r\n乙\r\n"
        val session = EditorSession(original)
        session.text.edit { selection = TextRange(original.indexOf('甲'), original.indexOf('乙') + 1) }
        session.format(FormatAction.BULLET)
        assertEquals(original.replace("甲", "- 甲").replace("乙", "- 乙"), session.text.text.toString())
        session.undo()
        assertEquals(original, session.text.text.toString())
    }

    @Test fun fiftyKiBAndOneMiBDocumentsKeepOriginalBytesAndRanges() {
        for (size in listOf(50 * 1024, 1024 * 1024)) {
            val original = ("中文 long text **内容**\r\n\r\n".repeat(size / 25 + 1)).take(size)
            val parsed = MarkdownParser().parse(original)
            assertEquals(original, parsed.source)
            assertTrue(parsed.blocks.isNotEmpty())
            assertEquals(original, EditorSession(original).text.text.toString())
        }
    }
}
