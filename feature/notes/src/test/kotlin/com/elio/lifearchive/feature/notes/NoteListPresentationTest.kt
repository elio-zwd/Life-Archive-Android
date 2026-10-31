package com.elio.lifearchive.feature.notes

import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.domain.notes.*
import org.junit.Assert.*
import org.junit.Test

class NoteListPresentationTest {
    @Test fun rowPreservesFileNameAndCopiesDisplayMetadata() {
        val summary = NoteSummary(NoteId("note"), LibraryPath("本子/原文件名.md"), DocumentVersion("v1"), false)
        val detail = LibraryNote(
            summary = summary, pinned = true, created = 10, modified = 20, deleted = null,
            tags = listOf("标签", "项目", "正文内联"), title = "独立 YAML 标题", propertyTags = listOf("标签", "项目"), excerpt = "正文摘要",
            thumbnail = ThumbnailReference("../图%20一.png", LinkSyntax.MARKDOWN),
        )
        val presentation = noteListPresentation(listOf(summary), LibraryListing(listOf(detail), emptyList(), emptyList(), 0))
        val row = presentation.rows.single()
        assertEquals("原文件名", row.name)
        assertEquals("本子", row.folder)
        assertEquals("正文摘要", row.excerpt)
        assertEquals(20L, row.modified)
        assertTrue(row.pinned)
        assertEquals("#标签 #项目", row.tags)
        assertEquals("../图%20一.png", row.thumbnailTarget)
        assertEquals(LinkSyntax.MARKDOWN, row.thumbnailSyntax)
        assertEquals(NoteCover.Preset(), row.cover)
        assertSame(detail, presentation.details[summary.id])
    }
}
