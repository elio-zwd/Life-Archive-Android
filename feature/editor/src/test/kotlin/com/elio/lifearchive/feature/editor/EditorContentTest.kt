package com.elio.lifearchive.feature.editor

import androidx.compose.ui.text.TextRange
import com.elio.lifearchive.core.document.ContinuationContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class EditorContentTest {
    @Test fun cursorFocusAndSelectionReuseTheSameSnapshotAndDisplayInLongDocuments() {
        for (size in listOf(50 * 1024, 1024 * 1024)) {
            val raw = "正文 **保留**\n\n".repeat(size / 12)
            val session = EditorSession(raw)
            val initial = session.content
            val parsed = MarkdownParser().parse(raw)
            val display = session.display(parsed)
            repeat(20) { i ->
                session.text.edit { selection = TextRange(i * 50, i * 50 + 2) }
                session.requestInputFocus()
                assertSame(initial, session.content)
                assertSame(display, session.display(parsed))
                assertEquals(emptyList<EditorTextChange>(), session.changesSince(initial.revision))
            }
            session.edit { selection = TextRange(raw.length); append("新增") }
            val changed = session.content
            assertEquals(initial.revision + 1, changed.revision)
            assertSame(changed, session.content)
            session.undo(); assertEquals(raw, session.content.source)
            session.redo(); assertEquals(raw + "新增", session.content.source)
        }
    }

    @Test fun intervalQueriesVisitTheWindowAndStillFindOverlappingOuterStyles() {
        val ranges = (0 until 100_000).map { MarkRange(it * 20, it * 20 + 8, TextMark.BOLD) } + MarkRange(0, 2_000_000, TextMark.QUOTE)
        val index = EditorRangeIndex(ranges, MarkRange::start, MarkRange::end)
        val result = index.query(1_000_000, 1_000_100)
        assertEquals(6, result.values.size)
        assertTrue(result.values.any { it.mark == TextMark.QUOTE })
        assertTrue("查询不应遍历全文：${result.visited}", result.visited < 100)
    }

    @Test fun pendingEditsMapStylesAndMarkersWithoutFullDocumentProjection() {
        val raw = "# 标题\n\n**保留**\n\n末尾"
        val session = EditorSession(raw)
        val parsed = MarkdownParser().parse(raw)
        val display = session.display(parsed)
        session.edit { replace(0, 0, "新增\n") }
        session.edit { append("\n新一行") }
        val snapshot = session.content
        val changes = requireNotNull(session.changesSince(display.revision))
        val window = 0..snapshot.source.length
        // 两处分离变更保留中间的全部样式，不把它们合并成一个巨大的脏区。
        val styles = display.styles(window, changes, snapshot.source.length)
        assertTrue(styles.any { snapshot.source.substring(it.start, it.end) == "**保留**" })
        assertEquals(parsed.markers.map { it.copy(start = it.start + 3, end = it.end + 3) }, display.markers(window, changes))
        session.edit { replace(snapshot.source.indexOf("**"), snapshot.source.indexOf("**") + 1, "") }
        val edited = display.markers(0..session.content.source.length, requireNotNull(session.changesSince(display.revision)))
        assertTrue(edited.none { it.start == session.content.source.indexOf("*保留") })
    }

    @Test fun lateContextAndDisplayCannotInstallIntoANewerOrClosedSession() {
        val session = EditorSession("1. > 内容")
        val initial = session.content
        val context = ContinuationContext.build(initial.source)
        val display = EditorDisplaySnapshot(initial.revision, MarkdownParser().parse(initial.source))
        session.insert("新")
        assertFalse(session.installContext(initial, context))
        assertFalse(session.installDisplay(initial, display))
        val latest = session.content
        session.close()
        assertFalse(session.installContext(latest, context))
    }

    @Test fun indexedRangesMatchFullDiffForUnambiguousInsertionsDeletionsAndReplacements() {
        val raw = "# 标题\n\n**保留**\n\n> 引用 *斜体*\n\n`代码`\n末尾😀"
        val parsed = MarkdownParser().parse(raw)
        val random = kotlin.random.Random(20261024)
        var checked = 0
        repeat(150) {
            val start = random.nextInt(raw.length + 1)
            val end = random.nextInt(start, raw.length + 1)
            val replacement = listOf("", "Ω新增Ж", "Ω\nЖ", "Ω😀\n文字Ж")[it % 4]
            // 重复标记的删除可有多个等价字符差分；已知输入区间比全文猜测更精确。
            if (replacement.isEmpty() && (raw.getOrNull(start) == raw.getOrNull(end) || raw.getOrNull(start - 1) == raw.getOrNull(end - 1))) return@repeat
            checked++
            val session = EditorSession(raw)
            val display = session.display(parsed)
            session.edit { replace(start, end, replacement) }
            val current = session.content
            val changes = requireNotNull(session.changesSince(display.revision))
            val expected = EditorRenderProjection(0, parsed).forText(current.source)
            assertEquals("样式 $start..$end", expected.styles.toSet(), display.styles(0..current.source.length, changes, current.source.length).toSet())
            assertEquals("标记 $start..$end", expected.markers, display.markers(0..current.source.length, changes))
        }
        assertTrue(checked > 100)
    }
}
