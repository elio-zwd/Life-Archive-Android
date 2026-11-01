package com.elio.lifearchive.feature.notes

import com.elio.lifearchive.core.document.DocumentAnalyzer
import com.elio.lifearchive.core.document.LibraryPath
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class PastedNoteTest {
    @Test fun frontmatterWinsAndUnknownPropertiesAndNewlinesRemainUnchanged() {
        for (tags in listOf("tags: [学习, 编程]", "tags:\r\n  - 学习\r\n  - 编程")) {
            val raw = "\uFEFF---\r\ntitle: \"独立 YAML 标题\"\r\n$tags\r\ncustom: {keep: true}\r\n---\r\n# 正文标题\r\n\r\n**术语**和==结论==\r\n"
            val parsed = parsePastedNote(raw, null)
            assertEquals("独立 YAML 标题", parsed.name)
            assertEquals(listOf("学习", "编程"), parsed.tags)
            assertEquals(raw, parsed.body)
            assertNull(parsed.warning)
        }
    }

    @Test fun plainTextAndHeadingGetYamlWithoutChangingOriginalBody() {
        val raw = "# **阅读**与标记\r\n\r\n一句话一个重点。\r\n"
        val parsed = parsePastedNote(raw, null)
        assertEquals("阅读与标记", parsed.name)
        assertEquals("---\r\ntitle: \"阅读与标记\"\r\ntags: []\r\n---\r\n$raw", parsed.body)
        val text = parsePastedNote("首行文字\n第二行", null)
        assertEquals("首行文字", text.name)
        assertTrue(text.tags.isEmpty())
        assertEquals("甲".repeat(40), parsePastedNote("甲".repeat(60), null).name)
        val emoji = "甲".repeat(39) + "😀"
        val emojiNote = parsePastedNote(emoji + "不要截断表情", null)
        assertEquals(emoji, DocumentAnalyzer.analyze(emojiNote.body).title)
        com.elio.lifearchive.core.document.RawDocument(emojiNote.body).utf8()
    }

    @Test fun titleIsEscapedAndFilenameRemainsIndependent() {
        val parsed = parsePastedNote("# 标题含\"引号\"与路径 / API:示例", null)
        assertTrue(parsed.body.contains("title: \"标题含\\\"引号\\\"与路径 / API:示例\""))
        assertEquals("标题含-引号-与路径 - API-示例", parsed.name)
        assertEquals("标题含\"引号\"与路径 / API:示例", DocumentAnalyzer.analyze(parsed.body).title)
    }

    @Test fun wrapperIsRemovedButInternalCodeAndRealCodeNotesAreKept() {
        val raw = "---\ntitle: 示例\ntags: [编程, 学习]\n---\n# 示例\n\n```kotlin\nval n = 1\n```"
        assertEquals(raw, parsePastedNote("````markdown\n$raw\n````", null).body)
        assertEquals(raw, parsePastedNote("~~~md\n$raw\n~~~", null).body)
        val code = "```kotlin\nval n = 1\n```"
        assertTrue(parsePastedNote(code, null).body.endsWith(code))
        val multiple = "```md\n第一块\n```\n正文\n```\n第二块\n```"
        assertTrue(parsePastedNote(multiple, null).body.endsWith(multiple))
    }

    @Test fun unclosedYamlIsPreservedAndWarnedWithoutAddingAnotherHeader() {
        val raw = "---\ntitle: 未结束\ntags: [学习]\n正文"
        val parsed = parsePastedNote(raw, null, LocalDateTime.of(2026, 11, 1, 12, 30, 0))
        assertEquals(raw, parsed.body)
        assertNotNull(parsed.warning)
        assertEquals("粘贴记录-2026-11-01-123000", parsed.name)
    }

    @Test fun missingTitleInExistingYamlUsesHeadingWithoutRewritingYaml() {
        val raw = "---\nunknown: yes\n---\n# 初始标题\n正文"
        assertEquals("初始标题", parsePastedNote(raw, null).name)
        assertEquals(raw, parsePastedNote(raw, null).body)
    }

    @Test fun filenamesArePortableAndRespectFolderAndSuffixLimits() {
        for (title in listOf("CON", "nul.txt", "LPT1", "a/b\\c:d*e?f\"g<h>i|", "结尾. ", "😀".repeat(100))) {
            val name = pastedFileName(title, "资料")
            assertTrue(name, LibraryPath.isValid("资料/$name.md"))
            assertFalse(name.contains('/'))
            assertFalse(name.contains('\\'))
        }
        val folder = "甲".repeat(110) + "/" + "乙".repeat(110)
        val name = pastedFileName("长标题".repeat(100), folder, " (10)")
        assertTrue(LibraryPath.isValid("$folder/$name.md"))
        assertTrue(name.endsWith(" (10)"))
    }
}
