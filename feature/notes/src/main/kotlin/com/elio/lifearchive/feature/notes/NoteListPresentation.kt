package com.elio.lifearchive.feature.notes

import androidx.compose.runtime.Immutable
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.domain.notes.LibraryListing
import com.elio.lifearchive.domain.notes.LibraryNote
import com.elio.lifearchive.domain.notes.NoteId
import com.elio.lifearchive.domain.notes.NoteSummary

/** 行只持有不可变显示值，进度等无关状态变化时可以跳过整行重组。 */
@Immutable
internal data class NoteRowUi(
    val id: String,
    val name: String,
    val subtitle: String,
    val tags: String,
    val management: String?,
    val excerpt: String,
    val folder: String,
    val modified: Long?,
    val pinned: Boolean,
    val thumbnailTarget: String?,
    val thumbnailSyntax: LinkSyntax?,
)

internal class NoteListPresentation(
    val rows: List<NoteRowUi>,
    val details: Map<NoteId, LibraryNote>,
)

internal fun noteListPresentation(notes: List<NoteSummary>, listing: LibraryListing?): NoteListPresentation {
    // 一份列表只关联一次；不得在 LazyColumn 每行组合时线性查找整库。
    val details = listing?.notes?.associateBy { it.summary.id }.orEmpty()
    return NoteListPresentation(notes.map { note ->
        val detail = details[note.id]
        NoteRowUi(
            id = note.id.value,
            name = note.path.value.substringAfterLast('/').removeSuffix(".md"),
            subtitle = if (note.indexPending) "正文已保存 · 索引待更新" else note.path.value,
            tags = detail?.propertyTags?.joinToString(" ") { "#$it" }.orEmpty(),
            management = detail?.let { if (it.deleted != null) "恢复／永久删除" else if (it.pinned) "已置顶 · 管理" else "管理" },
            excerpt = detail?.excerpt.orEmpty(),
            folder = note.path.value.substringBeforeLast('/', ""),
            modified = detail?.modified,
            pinned = detail?.pinned == true,
            thumbnailTarget = detail?.thumbnail?.target,
            thumbnailSyntax = detail?.thumbnail?.syntax,
        )
    }, details)
}
