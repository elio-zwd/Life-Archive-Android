package com.elio.lifearchive.domain.notes

import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable

@Serializable data class SyncEntry(val key: String, val kind: String, val path: String, val hash: String, val size: Long)
@Serializable data class SyncIssue(val path: String, val message: String, val key: String? = null)
@Serializable data class SyncInventory(val files: List<SyncEntry>, val issues: List<SyncIssue> = emptyList())
@Serializable data class SyncTrashItem(val token: String, val entry: SyncEntry)

/** 网络层只能提交已暂存的本地流；锁内不得等待网络或调用其他公开 Repository。 */
interface NotesSyncRepository {
    suspend fun inventory(): NoteResult<SyncInventory>
    suspend fun copy(entry: SyncEntry, output: OutputStream): NoteResult<Unit>
    suspend fun matches(key: String, expected: SyncEntry?): NoteResult<Boolean>
    suspend fun apply(expected: SyncEntry?, desired: SyncEntry?, input: InputStream? = null): NoteResult<Unit>
    suspend fun attachmentTrash(): NoteResult<List<SyncTrashItem>>
    suspend fun restoreAttachment(token: String): NoteResult<Unit>
}
