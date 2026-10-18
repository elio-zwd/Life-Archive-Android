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
    data class StorageFailure(val reason: String, val recoveryRequired: Boolean = false) : NoteResult<Nothing>
}

/** 所有正文写入经过此接口；Flow 只传元数据，不用于回写每个按键。 */
interface NotesRepository {
    fun observeNotes(): Flow<List<NoteSummary>>
    suspend fun initialize(): NoteResult<Unit>
    suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot>
    suspend fun read(id: NoteId): NoteResult<NoteSnapshot>
    suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long? = null): NoteResult<NoteSnapshot>
    suspend fun readDraft(id: NoteId): NoteResult<Draft?>
    suspend fun writeDraft(draft: Draft): NoteResult<Unit>
    suspend fun rebuildIndex(): NoteResult<Unit>
    suspend fun importAttachment(path: String, input: InputStream): NoteResult<LibraryPath>
    suspend fun copyAttachment(path: LibraryPath, output: OutputStream): NoteResult<Unit>
}
