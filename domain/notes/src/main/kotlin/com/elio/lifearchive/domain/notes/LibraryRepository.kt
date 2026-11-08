package com.elio.lifearchive.domain.notes

import com.elio.lifearchive.core.document.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class NoteSort { MODIFIED_DESC, MODIFIED_ASC, CREATED_DESC, CREATED_ASC, NAME }
data class NotesQuery(val text: String = "", val folder: String? = null, val tag: String? = null, val sort: NoteSort = NoteSort.MODIFIED_DESC, val trash: Boolean = false, val topic: String? = null, val kind: NoteKind? = null, val selectedTags: List<String> = emptyList(), val tagMatch: TagMatch = TagMatch.ALL)
data class ThumbnailReference(val target: String, val syntax: LinkSyntax)
data class LibraryNote(val summary: NoteSummary, val pinned: Boolean, val created: Long, val modified: Long, val deleted: Long?, val tags: List<String>, val title: String?, val matchOffset: Int = 0, val propertyTags: List<String> = emptyList(), val excerpt: String = "", val thumbnail: ThumbnailReference? = null, val cover: NoteCover = NoteCover.Preset(), val contentTimes: com.elio.lifearchive.core.document.ContentTimes = com.elio.lifearchive.core.document.ContentTimes(), val timeUncertain: Boolean = false)
data class LibraryListing(val notes: List<LibraryNote>, val folders: List<String>, val tags: List<String>, val pendingIndex: Int, val topics: List<String> = NoteOrganization.initialTopics)
data class ResolvedLink(val note: NoteId? = null, val attachment: LibraryPath? = null, val location: DocumentLocation = DocumentLocation(), val missing: Boolean = false, val ambiguous: List<String> = emptyList())
data class ResourceInfo(val path: LibraryPath, val length: Long, val mime: String)
/** 流的关闭责任归调用方；底层不向业务暴露私有文件路径。 */
data class ResourceStream(val info: ResourceInfo, val stream: InputStream)
data class ImportEntry(val path: String, val open: () -> InputStream)
data class PreparedImport(val token: String, val folder: String, val notes: Int, val attachments: Int, val unresolved: List<String>, val foreignTags: List<String> = emptyList(), val tagDecisions: Map<String, String?> = emptyMap())
data class LibraryProgress(val operation: String, val completed: Int, val total: Int)

interface LibraryRepository {
    suspend fun previewOrganization(changes: List<OrganizationChange>): NoteResult<OrganizationPreview> = NoteResult.StorageFailure("当前笔记库不支持整理")
    suspend fun applyOrganization(preview: OrganizationPreview): NoteResult<OrganizationResult> = NoteResult.StorageFailure("当前笔记库不支持整理")
    suspend fun organizationSuggestions(): NoteResult<List<OrganizationSuggestion>> = NoteResult.StorageFailure("当前笔记库不支持整理")
    suspend fun previewTaxonomy(change: TaxonomyChange): NoteResult<TaxonomyPreview> = NoteResult.StorageFailure("当前笔记库不支持分类管理")
    suspend fun applyTaxonomy(preview: TaxonomyPreview): NoteResult<Unit> = NoteResult.StorageFailure("当前笔记库不支持分类管理")
    val progress: Flow<LibraryProgress?> get() = flowOf(null)
    suspend fun query(query: NotesQuery = NotesQuery()): NoteResult<LibraryListing>
    suspend fun metadata(id: NoteId): NoteResult<LibraryNote> = when (val result = query()) {
        is NoteResult.Success -> result.value.notes.firstOrNull { it.summary.id == id }?.let { NoteResult.Success(it) } ?: NoteResult.NotFound
        is NoteResult.Committed -> result.value.notes.firstOrNull { it.summary.id == id }?.let { NoteResult.Success(it) } ?: NoteResult.NotFound
        is NoteResult.Conflict -> result
        NoteResult.DraftConflict -> NoteResult.DraftConflict
        NoteResult.NotFound -> NoteResult.NotFound
        NoteResult.PathConflict -> NoteResult.PathConflict
        is NoteResult.InvalidPath -> result
        is NoteResult.InvalidPosition -> result
        is NoteResult.StorageFailure -> result
    }
    suspend fun createFolder(path: String): NoteResult<Unit>
    suspend fun relocateFolder(from: String, to: String): NoteResult<Unit>
    suspend fun trashFolder(path: String): NoteResult<Unit>
    suspend fun setPinned(id: NoteId, pinned: Boolean): NoteResult<Unit>
    suspend fun setCover(id: NoteId, cover: NoteCover): NoteResult<Unit> = NoteResult.StorageFailure("当前笔记库不支持封面设置")
    suspend fun coverImages(id: NoteId): NoteResult<List<CoverImage>> = NoteResult.Success(emptyList())
    suspend fun setTags(id: NoteId, expected: DocumentVersion, tags: List<String>): NoteResult<Unit>
    suspend fun relocate(id: NoteId, expected: DocumentVersion, path: String): NoteResult<Unit>
    suspend fun trash(id: NoteId, expected: DocumentVersion): NoteResult<Unit>
    suspend fun restore(id: NoteId, path: String? = null): NoteResult<Unit>
    suspend fun purge(id: NoteId): NoteResult<Unit>
    suspend fun resolve(from: NoteId, target: String, syntax: LinkSyntax = LinkSyntax.WIKI): NoteResult<ResolvedLink>
    suspend fun openResource(path: LibraryPath, offset: Long = 0, length: Long? = null): NoteResult<ResourceStream>
    suspend fun prepareImport(entries: List<ImportEntry>, folder: String): NoteResult<PreparedImport>
    suspend fun commitImport(prepared: PreparedImport): NoteResult<Unit>
    suspend fun discardImport(prepared: PreparedImport)
    suspend fun export(output: OutputStream, ids: Set<NoteId>? = null): NoteResult<Unit>
}
