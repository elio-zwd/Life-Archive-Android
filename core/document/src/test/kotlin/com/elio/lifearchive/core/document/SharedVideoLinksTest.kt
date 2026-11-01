package com.elio.lifearchive.core.document

import org.junit.Assert.*
import org.junit.Test

class SharedVideoLinksTest {
    @Test fun extractsTheActualXiaohongshuAndDouyinShareSamples() {
        assertEquals(listOf("https://xhslink.cn/o/6va5s9Fs9CN"), extractSharedWebLinks("学会这三个机位，你的人像摄影就会很牛！ https://xhslink.cn/o/6va5s9Fs9CN 复制文本后进入【小红书】，直接查看笔记。"))
        assertEquals(listOf("https://v.douyin.com/ySydEn8zeT8/"), extractSharedWebLinks("4.89 复制打开抖音，看看【23班潘达的作品】大肥鱼的同学聚会 # DeepSeek # 大肥鱼... https://v.douyin.com/ySydEn8zeT8/ fOK:/ Q@K.Wz :7pm 06/13"))
    }
    @Test fun preservesQueriesFragmentsAndBalancedPathPunctuationWhileRemovingWrappers() {
        val url = "https://example.test/a(b)?from=x%2Fy&n=2#part"
        assertEquals(listOf(url), extractSharedWebLinks("链接（$url），再试【$url】。"))
        assertEquals(listOf("https://example.test/a(b)"), extractSharedWebLinks("[打开](https://example.test/a(b))"))
        assertEquals(listOf("https://example.test/x?"), extractSharedWebLinks("https://example.test/x?"))
        assertEquals(listOf("https://example.test/x"), extractSharedWebLinks("https://example.test/x)}。"))
        assertEquals(listOf("https://example.test/x", "http://other.test/y/"), extractSharedWebLinks("https://example.test/x\u00A0http://other.test/y/"))
    }
    @Test fun ignoresInvalidAndNonWebAddressesAndDeduplicatesWithoutGuessing() {
        assertEquals(emptyList<String>(), extractSharedWebLinks("分享口令abc ftp://example.test/x content://abc https://"))
        assertEquals(listOf("https://example.test/a", "https://example.test/b"), extractSharedWebLinks("https://example.test/a https://example.test/a https://example.test/b"))
    }
    @Test fun replacesOnlyTheScalarAndKeepsCommentsBodyUnknownPropertiesBomAndCrLf() {
        val raw = "\uFEFF---\r\ntitle: 原标题\r\nsource:  '错误网址'  # 原注释\r\ntags: [生活, 摄影]\r\ncustom: {nested: yes}\r\n---\r\n# 正文\r\n正文 source: 不要修改\r\n"
        val url = "https://example.test/video?a=1&b=2#part"
        val expected = raw.replace("'错误网址'", "\"$url\"")
        assertEquals(expected, NoteSource.withUrl(raw, url))
        assertEquals(url, NoteSource.read(expected))
    }
    @Test fun addsOnlySourceWhenMissingAndHandlesQuotedKeysAndEmptyValues() {
        val url = "https://example.test/v/"
        assertEquals("\uFEFF---\r\nsource: \"$url\"\r\n---\r\n正文\r\n", NoteSource.withUrl("\uFEFF正文\r\n", url))
        assertEquals("---\nsource: \"$url\"\ntitle: 保留\n---\n正文", NoteSource.withUrl("---\ntitle: 保留\n---\n正文", url))
        for (key in listOf("source", "\"source\"", "'source'")) {
            val raw = "---\n$key: \"\" # keep\n---\n正文"
            assertEquals(raw.replace("\"\"", "\"$url\""), NoteSource.withUrl(raw, url))
        }
        val commented = NoteSource.withUrl("---\nsource: # keep\n---\n正文", url)
        assertTrue(commented.contains("\"$url\" # keep"))
    }
    @Test fun jsonFrontmatterPreservesUnknownMembersAndRejectsDuplicateOrStructuredSource() {
        val url = "https://example.test/?a=1&b=2"
        val raw = "---\n{ \"title\":\"标题\", \"source\": \"wrong\", \"other\":{\"source\":\"nested\"} }\n---\n正文"
        assertEquals(raw.replace("\"wrong\"", "\"$url\""), NoteSource.withUrl(raw, url))
        val missing = "---\n{\"title\":\"标题\"}\n---\n正文"
        assertTrue(NoteSource.withUrl(missing, url).contains("{\"source\":\"$url\",\"title\":\"标题\"}"))
        for (body in listOf("{\"source\":\"a\",\"sour\\u0063e\":\"b\"}", "{\"source\":[\"a\"]}")) {
            assertThrows(IllegalArgumentException::class.java) { NoteSource.withUrl("---\n$body\n---\n正文", url) }
        }
    }
    @Test fun unsupportedYamlOrDuplicateKeysNeverProducesAReplacement() {
        for (raw in listOf("---\nsource: wrong", "---\nsource: a\nsource: b\n---\n正文", "---\nsource: |\n  old\n---\n正文", "---\nsource: &url old\n---\n正文", "---\nsource: *url\n---\n正文", "---\nsource:\n  url: x\n---\n正文", "---\nsource: [x]\n---\n正文", "---\n  source: x\n---\n正文")) {
            assertThrows(raw, IllegalArgumentException::class.java) { NoteSource.withUrl(raw, "https://example.test/v") }
        }
        assertThrows(IllegalArgumentException::class.java) { NoteSource.withUrl("正文", "https://") }
        val invalidJson = assertThrows(IllegalArgumentException::class.java) { NoteSource.withUrl("---\n{source: bad}\n---\n正文", "https://example.test/v") }
        assertTrue(invalidJson.message!!.contains("源码"))
    }
}
