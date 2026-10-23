package com.elio.lifearchive.core.document

import org.junit.Assert.*
import org.junit.Test

class ReadingProjectionTest {
    @Test fun projectsWholeDocumentWithSharedReferencesAndSourceRanges() {
        val raw = "\uFEFF---\r\nunknown: preserve\r\n---\r\n# 标题\r\n\r\n| 项目 | 内容 |\r\n| --- | --- |\r\n| **粗体** | [引用][r] |\r\n\r\n正文[^1] ^block\r\n\r\n[r]: folder/a.md\r\n[^1]: 脚注\r\n"
        val result = HtmlProjection.project(raw)
        assertEquals(HtmlProjection.render(raw), result.html)
        assertTrue(result.html.contains("<strong")); assertTrue(result.html.contains("folder/a.md")); assertTrue(result.html.contains("footnotes"))
        assertTrue(result.ranges.isNotEmpty()); assertTrue(result.ranges.all { it.start >= 0 && it.end in it.start..raw.length })
        assertEquals(raw.indexOf("正文"), result.blocks.single { it.name == "block" }.start)
        assertEquals(documentDigest(raw), result.digest)
        assertNotEquals(result.digest, HtmlProjection.project(raw + "\n").digest)
    }
}
