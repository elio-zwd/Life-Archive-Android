package com.elio.lifearchive.domain.notes

import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.RawDocument
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class NoteId(val value: String)

data class NoteSummary(val id: NoteId, val path: LibraryPath, val version: DocumentVersion, val indexPending: Boolean)
data class NoteSnapshot(val summary: NoteSummary, val document: RawDocument)
data class Draft(val noteId: NoteId, val baseVersion: DocumentVersion, val revision: Long, val document: RawDocument)

sealed interface NoteResult<out T> {
    data class Success<T>(val value: T) : NoteResult<T>
    data class Committed<T>(val value: T, val indexPending: Boolean) : NoteResult<T>
    data class Conflict(val actualVersion: DocumentVersion) : NoteResult<Nothing>
    data object DraftConflict : NoteResult<Nothing>
    data object NotFound : NoteResult<Nothing>
    data object PathConflict : NoteResult<Nothing>
    data class InvalidPath(val reason: String) : NoteResult<Nothing>
    data class InvalidPosition(val reason: String) : NoteResult<Nothing>
    data class StorageFailure(val reason: String, val recoveryRequired: Boolean = false) : NoteResult<Nothing>
}

fun NoteResult<*>.failureMessage(): String = when (this) {
    is NoteResult.Success, is NoteResult.Committed -> "操作已完成"
    is NoteResult.Conflict -> "内容版本已变化，请重新打开后操作；当前草稿保留"
    NoteResult.DraftConflict -> "草稿修订已变化，请先处理已有草稿"
    NoteResult.NotFound -> "资料不存在或已进入回收站"
    NoteResult.PathConflict -> "目标名称或大小写冲突，请更换路径"
    is NoteResult.InvalidPath -> reason
    is NoteResult.InvalidPosition -> reason
    is NoteResult.StorageFailure -> reason + if (recoveryRequired) "（需完成中断恢复后再操作）" else ""
}

/** 所有正文写入经过此接口；Flow 只传元数据，不用于回写每个按键。 */
interface NotesRepository {
    fun observeNotes(): Flow<List<NoteSummary>>
    suspend fun initialize(): NoteResult<Unit>
    suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot>
    suspend fun createManaged(path: String, document: RawDocument, authored: Boolean = true): NoteResult<NoteSnapshot> = create(path, document)
    suspend fun saveContent(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long? = null): NoteResult<NoteSnapshot> = save(id, expected, document, acknowledgedDraftRevision)
    suspend fun read(id: NoteId): NoteResult<NoteSnapshot>
    suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long? = null): NoteResult<NoteSnapshot>
    suspend fun readDraft(id: NoteId): NoteResult<Draft?>
    suspend fun writeDraft(draft: Draft): NoteResult<Unit>
    suspend fun readReadingOffset(id: NoteId): NoteResult<Int>
    suspend fun updateReadingOffset(id: NoteId, offset: Int): NoteResult<Unit>
    suspend fun rebuildIndex(): NoteResult<Unit>
    suspend fun importAttachment(path: String, input: InputStream): NoteResult<LibraryPath>
    suspend fun copyAttachment(path: LibraryPath, output: OutputStream): NoteResult<Unit>
}
