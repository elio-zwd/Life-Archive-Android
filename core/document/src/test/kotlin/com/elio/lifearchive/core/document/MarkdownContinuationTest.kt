package com.elio.lifearchive.core.document

import org.junit.Assert.*
import org.junit.Test

class MarkdownContinuationTest {
    private fun continued(raw: String, at: Int = raw.length): String {
        val change = requireNotNull(MarkdownContinuation.calculate(raw, at)) { raw }
        assertTrue(raw, change.isValid(raw.length, at))
        return raw.replaceRange(change.start, change.end, change.replacement)
    }

    @Test fun allMixedContainerOrdersProduceValidPatchesAndKeepOriginalText() {
        val parts = listOf("> ", "- ", "1. ", "- [x] ")
        for (a in parts) for (b in parts) for (c in parts) {
            val raw = a + b + c + "中文😀"
            val result = continued(raw)
            assertTrue(raw, result.startsWith(raw + "\n"))
            val context = ContinuationContext.build(raw)
            assertEquals(MarkdownContinuation.calculate(raw, raw.length), MarkdownContinuation.calculate(raw as CharSequence, raw.length, context, raw.length, "\n"))
        }
        assertEquals("1. > 内容\n2. > ", continued("1. > 内容"))
        assertEquals("- [x] > 内容\n- [ ] > ", continued("- [x] > 内容"))
        assertEquals("> 9. > 内容\n> 10. > ", continued("> 9. > 内容"))
        assertEquals("1) > 中\n2) > 文😀", continued("1) > 中文😀", 6))
    }

    @Test fun emptyMixedItemsExitOnlyTheLastContainerAndKeepLineEndings() {
        assertEquals("1. ", continued("1. > "))
        assertEquals("1. > ", continued("1. > > "))
        assertEquals("> ", continued("> 1. "))
        assertEquals("- [ ] ", continued("- [ ] > "))
        assertEquals("前文\r\n9. > 中文😀\r\n10. > ", continued("前文\r\n9. > 中文😀"))
        assertNull(MarkdownContinuation.calculate("999999999. 内容", 13))
        val raw = "> ".repeat(32) + "内容"
        assertNull(MarkdownContinuation.calculate(raw, raw.length))
        assertFalse(NewlineContinuation(5, 2, "", -1).isValid(8, 2))
    }

    @Test fun invalidatedContextRescansChangedProtectionAndDoesNotReuseStaleFenceState() {
        val raw = "普通\n\n1. > 内容"
        val context = ContinuationContext.build(raw)
        val fenced = raw.replace("普通", "```")
        assertNull(MarkdownContinuation.calculate(fenced, fenced.length, context, 0, "\n"))
        val code = "```\n\n1. > 内容"
        val opened = ContinuationContext.build(code)
        val normal = code.replace("```", "普通")
        assertNotNull(MarkdownContinuation.calculate(normal, normal.length, opened, 0, "\n"))
        for (source in listOf("---\nvalue: x\n1. > yaml", "```\n1. > code", "<div>\n1. > html", "> | h |\n> | --- |\n> | - cell |", "    1. > code")) {
            assertNull(source, MarkdownContinuation.calculate(source, source.length, ContinuationContext.build(source), source.length, "\n"))
        }
    }

    @Test fun oneMiBCheckpointQueriesAndRepeatedEnterReadOnlyTheChangedTail() {
        val raw = "正文 **保留**\n\n".repeat(100_000) + "1. > 内容"
        val context = ContinuationContext.build(raw)
        var current = raw
        repeat(20) {
            val measured = CountedText(current)
            val patch = requireNotNull(MarkdownContinuation.calculate(measured, current.length, context, raw.length, "\n"))
            assertTrue("第 $it 次回车扫描了 ${measured.reads} 个字符", measured.reads < 16_384)
            current = current.replaceRange(patch.start, patch.end, patch.replacement) + "内容"
        }
        assertNull(MarkdownContinuation.calculate(current, current.length, null, 0, "\n"))
        val giant = "1. " + "字".repeat(1024 * 1024)
        assertNull(MarkdownContinuation.calculate(giant, giant.length, ContinuationContext.build(giant), giant.length, "\n"))
    }

    private class CountedText(private val value: String) : CharSequence {
        var reads = 0
        override val length get() = value.length
        override fun get(index: Int): Char { reads++; return value[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence { reads += endIndex - startIndex; return value.substring(startIndex, endIndex) }
        override fun toString(): String = error("输入路径不应复制全文")
    }
}
