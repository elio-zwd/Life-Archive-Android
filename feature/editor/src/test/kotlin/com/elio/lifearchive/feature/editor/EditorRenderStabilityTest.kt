package com.elio.lifearchive.feature.editor

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.elio.lifearchive.domain.notes.CacheStream
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class EditorRenderStabilityTest {
    @Test fun consecutiveEmptyLinesKeepUnchangedHighlightAndDoNotHideEditedMarkers() {
        val raw = "# 标题\n\n**加粗**\n末尾"
        val projection = EditorRenderProjection(7, MarkdownParser().parse(raw))
        var current = raw
        repeat(20) {
            current += "\n"
            val displayed = projection.forText(current)
            assertEquals(current, displayed.source)
            assertEquals(projection.document.styles, displayed.styles)
            assertEquals(projection.document.markers, displayed.markers)
        }
        val changed = projection.forText(raw.replace("**加粗**", "*加粗**"))
        assertTrue(changed.markers.none { it.start == raw.indexOf("**") })
    }

    @Test fun rangesMoveWithInsertedTextAndStylesNeverCrossNewlineCharacters() {
        val raw = "普通\r\n**高亮**\r\n"
        val projected = EditorRenderProjection(1, MarkdownParser().parse(raw)).forText("新增\r\n$raw")
        val bold = projected.styles.first { it.mark == TextMark.BOLD }
        assertEquals("**高亮**", projected.source.substring(bold.start, bold.end))
        assertEquals(listOf("甲", "乙"), styleLines("甲\r\n乙\n", 0, 5).map { "甲\r\n乙\n".substring(it.first, it.last + 1) })
    }

    @Test fun affectedBlockGetsNewStylesBeforeFullDocumentParsingFinishes() {
        val raw = "# 保留标题\n\n普通正文\n\n**保留粗体**\n"
        val changed = raw.replace("普通正文", "*新增斜体*")
        val projected = EditorRenderProjection(1, MarkdownParser().parse(raw)).reparseChangedBlock(changed)
        assertEquals(changed, projected.source)
        assertTrue(projected.styles.any { it.mark == TextMark.ITALIC && changed.substring(it.start, it.end) == "*新增斜体*" })
        assertTrue(projected.styles.any { it.mark == TextMark.HEADING && it.start == 0 })
        assertTrue(projected.styles.any { it.mark == TextMark.BOLD && changed.substring(it.start, it.end) == "**保留粗体**" })
    }

    @Test fun resourceRangesSupportSuffixAndReturn416WithoutLeakingProbeStreams() = runBlocking {
        assertEquals(8L..9L, WebReaderRenderer.requestedRange("bytes=-2", 10))
        assertEquals(4L..9L, WebReaderRenderer.requestedRange("bytes=4-20", 10))
        assertNull(WebReaderRenderer.requestedRange("bytes=10-", 10)); assertNull(WebReaderRenderer.requestedRange("bytes=4-2", 10))
        var closed = 0
        suspend fun open(start: Long, count: Long?): CacheStream = CacheStream("application/pdf", 10, object : java.io.ByteArrayInputStream(ByteArray((count ?: 10).toInt()) { (start + it).toByte() }) { override fun close() { closed++; super.close() } })
        val invalid = WebReaderRenderer.resourceResponse("bytes=10-", ::open)
        assertEquals(416, invalid.statusCode); assertEquals("bytes */10", invalid.responseHeaders["Content-Range"]); assertEquals(1, closed)
        val valid = WebReaderRenderer.resourceResponse("bytes=2-4", ::open)
        assertEquals(206, valid.statusCode); assertEquals("bytes 2-4/10", valid.responseHeaders["Content-Range"])
        assertArrayEquals(byteArrayOf(2, 3, 4), valid.data.use { it.readBytes() }); assertEquals(3, closed)
    }
}
