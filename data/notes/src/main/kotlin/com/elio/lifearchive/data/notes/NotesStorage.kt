package com.elio.lifearchive.data.notes

import android.content.Context
import androidx.room.Room
import com.elio.lifearchive.core.backup.BackupParticipant
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.backup.atomicWrite
import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.domain.notes.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 每个私有库只创建一个实例；数据库、文件路径与实现类型不暴露给 feature。 */
class NotesStorage private constructor(
    private val context: Context,
    private val root: File,
    private val gate: StorageGate,
) : AutoCloseable {
    private val json = Json
    private val documents = File(root, "documents")
    private val attachments = File(root, "attachments")
    private val drafts = File(root, "drafts")
    private val journal = File(root, "operations/save.json")
    private val summaries = MutableStateFlow<List<NoteSummary>>(emptyList())
    private val state = Room.databaseBuilder(context, StateDatabase::class.java, File(root, "state.db").absolutePath)
        .openHelperFactory(PreservingStateOpenHelperFactory()).build()
    private var index = newIndex()
    internal var fault: (String) -> Unit = {}

    @Serializable private data class SaveRecord(val note: NoteRecord, val draftRevision: Long? = null)
    @Serializable private data class DraftRecord(val id: String, val base: String, val revision: Long, val text: String)
    @Serializable private data class SnapshotState(val format: Int = 1, val notes: List<NoteRecord>)

    val repository: NotesRepository = Repository()
    val backupParticipant: BackupParticipant = Participant()

    companion object {
        fun open(context: Context, directory: File, gate: StorageGate): NotesStorage {
            check(directory.isDirectory || directory.mkdirs())
            return NotesStorage(context.applicationContext, directory, gate)
        }
    }

    private fun newIndex(): IndexDatabase = Room.databaseBuilder(context, IndexDatabase::class.java, File(root, "index.db").absolutePath)
        .fallbackToDestructiveMigration(true).build()

    override fun close() { state.close(); index.close() }

    private fun NoteRecord.summary() = NoteSummary(NoteId(id), LibraryPath(path), DocumentVersion(version), indexPending)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun safeFile(base: File, path: LibraryPath): File = File(base, path.value).also {
        require(it.canonicalPath.startsWith(base.canonicalPath + File.separator)) { "路径超出库目录" }
    }
    private fun draftFile(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(drafts, "$id.json")
    }
    private fun pending(record: NoteRecord): File = File(safeFile(documents, LibraryPath(record.path)).parentFile, ".save-${record.id}.pending")

    private suspend fun publish() { summaries.value = state.notes().all().map { it.summary() } }

    private suspend fun recover() {
        if (!journal.exists()) return
        val operation = json.decodeFromString<SaveRecord>(journal.readText())
        val record = operation.note
        val target = safeFile(documents, LibraryPath(record.path))
        val temp = pending(record)
        if (!target.isFile || digest(target.readBytes()) != record.hash) {
            check(temp.isFile && digest(temp.readBytes()) == record.hash) { "保存记录缺少完整正文，需恢复" }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        state.notes().put(record.copy(indexPending = true))
        removeAcknowledgedDraft(operation)
        check(journal.delete())
        if (temp.exists()) check(temp.delete())
        project(record)
        publish()
    }

    private fun removeAcknowledgedDraft(operation: SaveRecord) {
        val file = draftFile(operation.note.id)
        if (file.exists() && operation.draftRevision != null) {
            val draft = json.decodeFromString<DraftRecord>(file.readText())
            if (draft.revision == operation.draftRevision && digest(RawDocument(draft.text).utf8()) == operation.note.hash) check(file.delete())
        }
    }

    private suspend fun project(record: NoteRecord): Boolean = try {
        fault("index")
        val text = RawDocument.fromUtf8(safeFile(documents, LibraryPath(record.path)).readBytes()).text
        index.projections().put(IndexRecord(record.id, record.version, text))
        state.notes().put(record.copy(indexPending = false))
        true
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    private suspend fun <T> access(block: suspend () -> NoteResult<T>): NoteResult<T> = try {
        gate.access { recover(); block() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        NoteResult.StorageFailure(e.message ?: "存储操作失败", journal.exists())
    }

    private suspend fun commit(record: NoteRecord, document: RawDocument, draftRevision: Long?): NoteResult<NoteSnapshot> {
        val target = safeFile(documents, LibraryPath(record.path))
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temp = pending(record)
        fault("write")
        FileOutputStream(temp).use { it.write(document.utf8()); it.fd.sync() }
        val operation = SaveRecord(record, draftRevision)
        atomicWrite(journal, json.encodeToString(operation).toByteArray())
        fault("journal")
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        fault("file")
        state.notes().put(record)
        removeAcknowledgedDraft(operation)
        check(journal.delete())
        val indexed = project(record)
        publish()
        return NoteResult.Committed(NoteSnapshot(record.copy(indexPending = !indexed).summary(), document), !indexed)
    }

    private inner class Repository : NotesRepository {
        override fun observeNotes(): Flow<List<NoteSummary>> = flow {
            val result = initialize()
            check(result is NoteResult.Success) { "笔记库尚未就绪：$result" }
            // 恢复可能多步更新内部状态；对观察者只发布门锁释放后的有效快照。
            emitAll(summaries.map { gate.access { state.notes().all().map { record -> record.summary() } } })
        }

        override suspend fun initialize(): NoteResult<Unit> = access {
            state.notes().all().filter { it.indexPending }.forEach { project(it) }
            publish()
            NoteResult.Success(Unit)
        }

        override suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot> = access {
            if (!LibraryPath.isValid(path) || !path.endsWith(".md")) return@access NoteResult.InvalidPath("需要跨平台合法的 .md 相对路径")
            val valid = LibraryPath(path)
            if (LibraryPath.conflicts(path, state.notes().all().map { it.path }) || safeFile(documents, valid).exists()) return@access NoteResult.PathConflict
            val record = NoteRecord(UUID.randomUUID().toString(), path, valid.collisionKey, UUID.randomUUID().toString(), digest(document.utf8()))
            commit(record, document, null)
        }

        override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            val bytes = safeFile(documents, LibraryPath(record.path)).readBytes()
            check(digest(bytes) == record.hash) { "正文与提交记录不一致，拒绝静默覆盖" }
            NoteResult.Success(NoteSnapshot(record.summary(), RawDocument.fromUtf8(bytes)))
        }

        override suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long?): NoteResult<NoteSnapshot> = access {
            val old = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            if (old.version != expected.value) return@access NoteResult.Conflict(DocumentVersion(old.version))
            check(digest(safeFile(documents, LibraryPath(old.path)).readBytes()) == old.hash) { "正文被存储层外修改" }
            commit(old.copy(version = UUID.randomUUID().toString(), hash = digest(document.utf8()), indexPending = true), document, acknowledgedDraftRevision)
        }

        override suspend fun readDraft(id: NoteId): NoteResult<Draft?> = access {
            if (state.notes().find(id.value) == null) return@access NoteResult.NotFound
            val file = draftFile(id.value)
            val draft = if (file.exists()) json.decodeFromString<DraftRecord>(file.readText()) else null
            NoteResult.Success(draft?.let { Draft(id, DocumentVersion(it.base), it.revision, RawDocument(it.text)) })
        }

        override suspend fun writeDraft(draft: Draft): NoteResult<Unit> = access {
            if (state.notes().find(draft.noteId.value) == null) return@access NoteResult.NotFound
            val file = draftFile(draft.noteId.value)
            val previous = if (file.exists()) json.decodeFromString<DraftRecord>(file.readText()) else null
            if (draft.revision <= 0 || (previous != null && draft.revision <= previous.revision)) return@access NoteResult.DraftConflict
            atomicWrite(file, json.encodeToString(DraftRecord(draft.noteId.value, draft.baseVersion.value, draft.revision, draft.document.text)).toByteArray())
            NoteResult.Success(Unit)
        }

        override suspend fun rebuildIndex(): NoteResult<Unit> = access {
            state.notes().invalidateIndex()
            try { index.projections().clear() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                index.close()
                check(context.deleteDatabase(File(root, "index.db").absolutePath)) { "无法清理损坏索引" }
                index = newIndex()
            }
            val ok = state.notes().all().map { project(it) }.all { it }
            publish()
            if (ok) NoteResult.Success(Unit) else NoteResult.StorageFailure("正文已保留，部分索引待重建")
        }

        override suspend fun importAttachment(path: String, input: InputStream): NoteResult<LibraryPath> = access {
            if (!LibraryPath.isValid(path)) return@access NoteResult.InvalidPath("非法附件路径")
            val valid = LibraryPath(path)
            if (LibraryPath.conflicts(path, attachments.walkTopDown().filter { it.isFile }.map { it.relativeTo(attachments).invariantSeparatorsPath }.toList())) return@access NoteResult.PathConflict
            val target = safeFile(attachments, valid)
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            val temp = File(root, "attachment-${UUID.randomUUID()}.pending")
            try {
                FileOutputStream(temp).use { out -> input.copyTo(out, 64 * 1024); out.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { if (temp.exists()) temp.delete() }
            NoteResult.Success(valid)
        }

        override suspend fun copyAttachment(path: LibraryPath, output: OutputStream): NoteResult<Unit> = access {
            val file = safeFile(attachments, path)
            if (!file.isFile) return@access NoteResult.NotFound
            file.inputStream().use { it.copyTo(output, 64 * 1024) }
            NoteResult.Success(Unit)
        }
    }

    private inner class Participant : BackupParticipant {
        override val id = "notes"

        override suspend fun snapshot(directory: File) {
            recover()
            val records = state.notes().all()
            atomicWrite(File(directory, "state.json"), json.encodeToString(SnapshotState(notes = records)).toByteArray())
            val docDest = File(directory, "documents").also { check(it.mkdirs()) }
            val draftDest = File(directory, "drafts").also { check(it.mkdirs()) }
            // 只导出已登记正文和草稿；提交前退出遗留的暂存文件不是用户内容。
            records.forEach { record ->
                val path = LibraryPath(record.path)
                val destination = safeFile(docDest, path)
                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                safeFile(documents, path).copyTo(destination)
                val draft = draftFile(record.id)
                if (draft.isFile) draft.copyTo(File(draftDest, draft.name))
            }
            val attachmentDest = File(directory, "attachments").also { check(it.mkdirs()) }
            if (attachments.exists()) attachments.copyRecursively(attachmentDest, overwrite = true)
        }

        override suspend fun validate(directory: File) {
            val stateFile = File(directory, "state.json")
            require(stateFile.length() in 1..(16 * 1024 * 1024))
            val content = json.decodeFromString<SnapshotState>(stateFile.readText())
            require(content.format == 1)
            require(content.notes.map { it.id }.distinct().size == content.notes.size)
            require(content.notes.map { it.pathKey }.distinct().size == content.notes.size)
            val checkedPaths = mutableListOf<String>()
            content.notes.forEach { record ->
                require(UUID.fromString(record.id).toString() == record.id)
                require(UUID.fromString(record.version).toString() == record.version)
                val path = LibraryPath(record.path)
                require(!LibraryPath.conflicts(path.value, checkedPaths))
                checkedPaths.add(path.value)
                require(path.value.endsWith(".md") && record.pathKey == path.collisionKey)
                val bytes = safeFile(File(directory, "documents"), path).readBytes()
                require(digest(bytes) == record.hash)
                RawDocument.fromUtf8(bytes)
            }
            val expectedDocuments = content.notes.map { it.path }.toSet()
            require(File(directory, "documents").walkTopDown().filter { it.isFile }.map { it.relativeTo(File(directory, "documents")).invariantSeparatorsPath }.toSet() == expectedDocuments)
            val ids = content.notes.map { it.id }.toSet()
            File(directory, "drafts").walkTopDown().filter { it.isFile }.forEach { file ->
                val draft = json.decodeFromString<DraftRecord>(file.readText())
                require(draft.id in ids && file.parentFile == File(directory, "drafts") && file.name == "${draft.id}.json" && draft.revision > 0)
                require(UUID.fromString(draft.base).toString() == draft.base)
            }
            val attachmentPaths = mutableListOf<String>()
            File(directory, "attachments").walkTopDown().filter { it.isFile }.forEach {
                val path = LibraryPath(it.relativeTo(File(directory, "attachments")).invariantSeparatorsPath).value
                require(!LibraryPath.conflicts(path, attachmentPaths))
                attachmentPaths.add(path)
            }
            require(directory.list().orEmpty().all { it in setOf("state.json", "documents", "drafts", "attachments") })
        }

        override suspend fun restore(directory: File) {
            validate(directory)
            // 引擎已持久保存旧快照；中途退出后会重新完整恢复旧快照。
            for (name in listOf("documents", "attachments", "drafts")) {
                val target = File(root, name)
                check(!target.exists() || target.deleteRecursively())
                check(target.mkdirs())
                val source = File(directory, name)
                if (source.exists()) source.copyRecursively(target, overwrite = true)
            }
            val content = json.decodeFromString<SnapshotState>(File(directory, "state.json").readText())
            state.notes().replace(content.notes)
            if (journal.exists()) check(journal.delete())
            try { index.projections().clear() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 状态已标记待重建。 */ }
            content.notes.forEach { project(it.copy(indexPending = true)) }
            publish()
        }
    }
}
