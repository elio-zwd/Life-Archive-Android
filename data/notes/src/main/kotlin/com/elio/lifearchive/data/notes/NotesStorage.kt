package com.elio.lifearchive.data.notes

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.elio.lifearchive.core.backup.BackupParticipant
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.backup.atomicWrite
import com.elio.lifearchive.core.document.DocumentVersion
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.document.DocumentAnalyzer
import com.elio.lifearchive.core.document.LinkSyntax
import com.elio.lifearchive.domain.notes.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

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
    private val batchJournal = File(root, "operations/batch.json")
    private val changes = MutableStateFlow(0L)
    private val activityProgress = MutableStateFlow<LibraryProgress?>(null)
    private data class CachedAnalysis(
        val analysis: com.elio.lifearchive.core.document.DocumentAnalysis,
        val lowercase: String,
        var hiddenRanges: List<IntRange>? = null,
    )
    private val analysisCache = mutableMapOf<String, CachedAnalysis>()
    private val state = Room.databaseBuilder(context, StateDatabase::class.java, File(root, "state.db").absolutePath)
        .openHelperFactory(PreservingStateOpenHelperFactory()).addMigrations(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN created INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN modified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS folders (path TEXT NOT NULL PRIMARY KEY)")
            }
        }).build()
    private var index = newIndex()
    internal var fault: (String) -> Unit = {}

    @Serializable private data class SaveRecord(val note: NoteRecord, val draftRevision: Long? = null)
    @Serializable private data class DraftRecord(val id: String, val base: String, val revision: Long, val text: String)
    @Serializable private data class SnapshotState(val format: Int = 1, val notes: List<NoteRecord>, val folders: List<FolderRecord> = emptyList())
    @Serializable private data class BatchRecord(val token: String, val committed: Boolean = false, val rolledBack: Boolean = false)

    val repository: NotesRepository = Repository()
    val library: LibraryRepository = Library()
    val backupParticipant: BackupParticipant = Participant()

    companion object {
        private val excerptFences = Regex("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)").toPattern()
        private val excerptInlineCode = Regex("(?<!`)(`+)(?!`)([^`]*?)\\1(?!`)").toPattern()
        private val excerptBackticks = Regex("(?<!`)`+(?!`)").toPattern()
        private val excerptIndented = Regex("(?m)^(?: {4}|\\t)[^\\r\\n]*").toPattern()
        private val excerptWikiImage = Regex("!\\[\\[[^]\\r\\n]+]]")
        private val excerptMarkdownImage = Regex("!\\[[^]\\r\\n]*]\\((?:[^()\\r\\n]|\\([^()\\r\\n]*\\))*\\)")
        private val excerptWikiLink = Regex("\\[\\[([^]\\r\\n]+)]]")
        private val excerptMarkdownLink = Regex("\\[([^]\\r\\n]+)]\\((?:[^()\\r\\n]|\\([^()\\r\\n]*\\))*\\)")
        private val excerptHtml = Regex("<[^>]*>")
        private val excerptQuote = Regex("(?m)^ {0,3}(?:>[ \\t]*)+")
        private val excerptLinePrefix = Regex("(?m)^[ \\t]*(?:#{1,6}[ \\t]+|(?:[-+*]|\\d+[.)])[ \\t]+(?:\\[[ xX]](?:[ \\t]+|$))?)")
        private val excerptFormatting = Regex("\\*\\*|__|~~|==|`|\\*")
        private val excerptWhitespace = Regex("\\s+")
        private val imageExtension = Regex("(?i)\\.(?:png|jpe?g|gif|webp|bmp|heic|heif|avif|svg)$")
        private val linkScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

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
    private fun documentFile(record: NoteRecord): File = if (record.deleted == null) safeFile(documents, LibraryPath(record.path)) else File(root, "trash/${record.id}.md")

    private suspend fun publish() { changes.value += 1 }

    private suspend fun recover() {
        if (batchJournal.exists()) {
            val batch = json.decodeFromString<BatchRecord>(batchJournal.readText())
            require(UUID.fromString(batch.token).toString() == batch.token)
            val rollback = File(root, "operations/rollback-${batch.token}")
            if (!batch.committed && !batch.rolledBack) {
                Participant().restore(rollback)
                atomicWrite(batchJournal, json.encodeToString(batch.copy(rolledBack = true)).toByteArray())
                fault("batch-rollback-complete")
            }
            check(!rollback.exists() || rollback.deleteRecursively())
            fault("batch-cleanup")
            check(batchJournal.delete())
        }
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
        val text = RawDocument.fromUtf8(documentFile(record).readBytes()).text
        index.projections().put(IndexRecord(record.id, record.version, text))
        analysisCache.remove("${record.id}:${record.version}")
        state.notes().put(record.copy(indexPending = false))
        true
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    private suspend fun <T> access(block: suspend () -> NoteResult<T>): NoteResult<T> = try {
        gate.access { recover(); block() }
    } catch (e: CancellationException) { activityProgress.value = null; throw e } catch (e: Exception) {
        activityProgress.value = null
        NoteResult.StorageFailure(e.message ?: "存储操作失败", journal.exists() || batchJournal.exists())
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
            emitAll(changes.map { gate.access { state.notes().all().filter { it.deleted == null }.map { record -> record.summary() } } })
        }

        override suspend fun initialize(): NoteResult<Unit> = access {
            state.notes().all().filter { it.created == 0L }.forEach { record ->
                val baseline = documentFile(record).lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                state.notes().put(record.copy(created = baseline, modified = baseline))
            }
            val pendingRecords = state.notes().all().filter { it.indexPending }
            pendingRecords.forEachIndexed { index, record -> activityProgress.value = LibraryProgress("建立索引", index, pendingRecords.size); project(record) }
            activityProgress.value = null
            publish()
            NoteResult.Success(Unit)
        }

        override suspend fun create(path: String, document: RawDocument): NoteResult<NoteSnapshot> = access {
            if (!LibraryPath.isValid(path) || !path.endsWith(".md", true)) return@access NoteResult.InvalidPath("需要跨平台合法的 .md 相对路径")
            val valid = LibraryPath(path)
            if (LibraryPath.conflicts(path, state.notes().all().filter { it.deleted == null }.map { it.path } + attachmentPaths() + state.notes().folders().map { "${it.path}/.folder" }) || safeFile(documents, valid).exists()) return@access NoteResult.PathConflict
            val now = System.currentTimeMillis()
            val record = NoteRecord(UUID.randomUUID().toString(), path, valid.collisionKey, UUID.randomUUID().toString(), digest(document.utf8()), created = now, modified = now)
            commit(record, document, null)
        }

        override suspend fun read(id: NoteId): NoteResult<NoteSnapshot> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            if (record.deleted != null) return@access NoteResult.NotFound
            val bytes = safeFile(documents, LibraryPath(record.path)).readBytes()
            check(digest(bytes) == record.hash) { "正文与提交记录不一致，拒绝静默覆盖" }
            NoteResult.Success(NoteSnapshot(record.summary(), RawDocument.fromUtf8(bytes)))
        }

        override suspend fun save(id: NoteId, expected: DocumentVersion, document: RawDocument, acknowledgedDraftRevision: Long?): NoteResult<NoteSnapshot> = access {
            val old = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            if (old.deleted != null) return@access NoteResult.NotFound
            if (old.version != expected.value) return@access NoteResult.Conflict(DocumentVersion(old.version))
            check(digest(safeFile(documents, LibraryPath(old.path)).readBytes()) == old.hash) { "正文被存储层外修改" }
            commit(old.copy(version = UUID.randomUUID().toString(), hash = digest(document.utf8()), indexPending = true, modified = System.currentTimeMillis()), document, acknowledgedDraftRevision)
        }

        override suspend fun readDraft(id: NoteId): NoteResult<Draft?> = access {
            if (state.notes().find(id.value) == null) return@access NoteResult.NotFound
            val file = draftFile(id.value)
            val draft = if (file.exists()) json.decodeFromString<DraftRecord>(file.readText()) else null
            NoteResult.Success(draft?.let { Draft(id, DocumentVersion(it.base), it.revision, RawDocument(it.text)) })
        }

        override suspend fun readReadingOffset(id: NoteId): NoteResult<Int> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            NoteResult.Success(record.readingOffset)
        }

        override suspend fun updateReadingOffset(id: NoteId, offset: Int): NoteResult<Unit> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            if (offset < 0) return@access NoteResult.InvalidPosition("阅读偏移不能为负数")
            state.notes().put(record.copy(readingOffset = offset))
            NoteResult.Success(Unit)
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
            analysisCache.clear()
            state.notes().invalidateIndex()
            try { index.projections().clear() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                index.close()
                check(context.deleteDatabase(File(root, "index.db").absolutePath)) { "无法清理损坏索引" }
                index = newIndex()
            }
            val records = state.notes().all()
            val ok = records.mapIndexed { index, record -> activityProgress.value = LibraryProgress("重建索引", index + 1, records.size); project(record) }.all { it }
            activityProgress.value = null
            publish()
            if (ok) NoteResult.Success(Unit) else NoteResult.StorageFailure("正文已保留，部分索引待重建")
        }

        override suspend fun importAttachment(path: String, input: InputStream): NoteResult<LibraryPath> = access {
            if (!LibraryPath.isValid(path) || path.endsWith(".md", true)) return@access NoteResult.InvalidPath("非法附件路径；Markdown 请使用笔记导入")
            val valid = LibraryPath(path)
            if (LibraryPath.conflicts(path, attachmentPaths() + state.notes().all().filter { it.deleted == null }.map { it.path } + state.notes().folders().map { "${it.path}/.folder" })) return@access NoteResult.PathConflict
            val target = safeFile(attachments, valid)
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            val temp = File(root, "attachment-${UUID.randomUUID()}.pending")
            try {
                FileOutputStream(temp).use { out -> input.copyTo(out, 64 * 1024); out.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { if (temp.exists()) temp.delete() }
            // 附件集合变化也会改变已有引用的解析结果，通知订阅者刷新派生展示。
            publish()
            NoteResult.Success(valid)
        }

        override suspend fun copyAttachment(path: LibraryPath, output: OutputStream): NoteResult<Unit> = access {
            val file = safeFile(attachments, path)
            if (!file.isFile) return@access NoteResult.NotFound
            file.inputStream().use { it.copyTo(output, 64 * 1024) }
            NoteResult.Success(Unit)
        }
    }

    /** 文件与 Room 不能共用事务；先保留逻辑旧快照，提交标记前一律回滚。 */
    private suspend fun batch(action: suspend () -> Unit): NoteResult<Unit> {
        val token = UUID.randomUUID().toString()
        val rollback = File(root, "operations/rollback-$token")
        try {
            Participant().snapshot(rollback.also { check(it.mkdirs()) })
            atomicWrite(batchJournal, json.encodeToString(BatchRecord(token)).toByteArray())
            fault("batch-journal")
            action()
            fault("batch-files")
            atomicWrite(batchJournal, json.encodeToString(BatchRecord(token, true)).toByteArray())
            fault("batch-committed")
            recover()
            publish()
            return NoteResult.Success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val committed = batchJournal.takeIf { it.exists() }?.let { json.decodeFromString<BatchRecord>(it.readText()).committed } == true
            if (batchJournal.exists()) recover() else rollback.deleteRecursively()
            if (committed) { publish(); return NoteResult.Success(Unit) }
            throw e
        }
    }

    private fun attachmentPaths(): List<String> = attachments.walkTopDown().filter { it.isFile }.map { it.relativeTo(attachments).invariantSeparatorsPath }.toList()
    private fun validateNamespace(files: List<String>, folders: List<String>) {
        val checked = mutableListOf<String>()
        files.forEach { require(LibraryPath.isValid(it) && !LibraryPath.conflicts(it, checked)) { "资料路径存在大小写或文件/目录冲突：$it" }; checked += it }
        folders.distinct().forEach { folder ->
            val marker = "$folder/.folder"
            require(LibraryPath.isValid(folder) && !LibraryPath.conflicts(marker, checked)) { "笔记本路径冲突：$folder" }
            checked += marker
        }
    }
    private fun resolvePath(from: String, target: String, paths: List<String>, syntax: LinkSyntax = LinkSyntax.WIKI): List<String> {
        val path = DocumentAnalyzer.decode(target.substringBefore('#')).removePrefix("/")
        if (path.isEmpty()) return listOf(from)
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(path)) return emptyList()
        val parent = from.substringBeforeLast('/', "")
        val relative = DocumentAnalyzer.normalizedPath(if (parent.isEmpty()) path else "$parent/$path")
        val absolute = DocumentAnalyzer.normalizedPath(path)
        val candidates = when {
            target.startsWith('/') -> listOf(absolute)
            syntax != LinkSyntax.WIKI || path.startsWith("./") || path.startsWith("../") -> listOf(relative)
            '/' !in path -> return paths.filter { it.substringAfterLast('/').let { name -> name.equals(path, true) || name.removeSuffix(".md").equals(path, true) } }
            else -> listOf(absolute, relative)
        }
        for (candidate in candidates.filterNotNull().distinct()) {
            val matches = paths.filter { it.equals(candidate, true) || (!candidate.endsWith(".md", true) && it.equals("$candidate.md", true)) }
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }
    private fun rewrite(source: String, from: String, newFrom: String, paths: List<String>, mapping: Map<String, String>): String {
        var result = source
        DocumentAnalyzer.analyze(source).links.asReversed().forEach { link ->
            val found = resolvePath(from, link.target, paths, link.syntax)
            require(found.size <= 1 || found.none { it in mapping }) { "存在歧义引用：${link.target}，请先明确路径" }
            if (found.size != 1) return@forEach
            val oldTarget = found.single()
            val newTarget = mapping[oldTarget] ?: oldTarget
            if (oldTarget == newTarget && from == newFrom) return@forEach
            val newPaths = paths.map { mapping[it] ?: it }
            if (oldTarget == newTarget && resolvePath(newFrom, link.target, newPaths, link.syntax) == listOf(newTarget)) return@forEach
            val original = source.substring(link.start, link.end)
            val fragment = (if ('#' in original) original.substringAfter('#') else link.target.substringAfter('#', "")).let { if (it.isEmpty()) "" else "#$it" }
            val originalPath = link.target.substringBefore('#')
            if (originalPath.isEmpty()) return@forEach
            if (link.mermaidNode != null) {
                val nodeTarget = if (originalPath.endsWith(".md", true)) newTarget else newTarget.removeSuffix(".md")
                val label = "\"" + (nodeTarget + fragment).replace("\"", "#quot;") + "\""
                result = result.replaceRange(link.start, link.end, if (link.implicitNode) "${link.mermaidNode}[$label]" else label)
                return@forEach
            }
            var destination = if (originalPath.startsWith('/')) "/$newTarget" else if (link.wiki && !originalPath.startsWith("./") && !originalPath.startsWith("../")) newTarget else {
                val parent = java.nio.file.Paths.get(newFrom).parent ?: java.nio.file.Paths.get("")
                parent.relativize(java.nio.file.Paths.get(newTarget)).toString().replace('\\', '/')
            }
            if (link.wiki && !DocumentAnalyzer.decode(originalPath).endsWith(".md", true)) destination = destination.removeSuffix(".md")
            if (DocumentAnalyzer.decode(originalPath) == destination) return@forEach
            if (!link.wiki) destination = DocumentAnalyzer.encode(destination)
            result = result.replaceRange(link.start, link.end, destination + fragment)
        }
        return result
    }
    private suspend fun ensureNoDraft(record: NoteRecord) {
        val file = draftFile(record.id)
        if (file.exists()) {
            val draft = json.decodeFromString<DraftRecord>(file.readText())
            require(digest(RawDocument(draft.text).utf8()) == record.hash) { "${record.path} 有未解决草稿，请先恢复或另存" }
        }
    }
    private suspend fun relocateAll(mapping: Map<String, String>, folderMap: ((String) -> String)? = null): NoteResult<Unit> {
        val records = state.notes().all().filter { it.deleted == null }
        val attachmentNames = attachmentPaths()
        val paths = records.map { it.path } + attachmentNames
        records.forEach { record ->
            val file = draftFile(record.id)
            if (file.isFile) {
                val draft = json.decodeFromString<DraftRecord>(file.readText())
                if (digest(RawDocument(draft.text).utf8()) != record.hash && (record.path in mapping || DocumentAnalyzer.analyze(draft.text).links.any { link -> resolvePath(record.path, link.target, paths, link.syntax).any { it in mapping } })) error("${record.path} 有受影响的未解决草稿，请先恢复或另存")
            }
        }
        val resulting = paths.map { mapping[it] ?: it }
        validateNamespace(resulting, state.notes().folders().map { folderMap?.invoke(it.path) ?: it.path })
        resulting.forEachIndexed { i, path ->
            require(LibraryPath.isValid(path) && !LibraryPath.conflicts(path, resulting.take(i))) { "目标存在名称或目录大小写冲突：$path" }
        }
        val updates = records.mapNotNull { record ->
            val file = documentFile(record); val bytes = file.readBytes()
            check(digest(bytes) == record.hash) { "正文校验失败：${record.path}" }
            val source = RawDocument.fromUtf8(bytes).text
            val path = mapping[record.path] ?: record.path
            val rewritten = rewrite(source, record.path, path, paths, mapping)
            if (path == record.path && rewritten == source) null else {
                ensureNoDraft(record)
                record to Pair(path, RawDocument(rewritten))
            }
        }
        return batch {
            val staged = updates.map { (old, change) -> Triple(old, change, documentFile(old)) }
            staged.forEach { (old, change, _) ->
                atomicWrite(safeFile(documents, LibraryPath(change.first)), change.second.utf8())
                val updated = old.copy(path = change.first, pathKey = LibraryPath(change.first).collisionKey, version = UUID.randomUUID().toString(), hash = digest(change.second.utf8()), modified = System.currentTimeMillis(), indexPending = true)
                state.notes().put(updated)
                draftFile(old.id).takeIf { it.exists() }?.let { check(it.delete()) }
                fault("batch-note")
            }
            staged.forEach { (old, change, oldFile) ->
                if (old.path != change.first && old.path !in updates.map { it.second.first }) {
                    val target = safeFile(documents, LibraryPath(change.first))
                    if (oldFile.exists() && !Files.isSameFile(oldFile.toPath(), target.toPath())) check(oldFile.delete())
                }
            }
            attachmentNames.filter { it in mapping && it != mapping[it] }.forEach { old ->
                val file = safeFile(attachments, LibraryPath(old)); val target = safeFile(attachments, LibraryPath(mapping.getValue(old)))
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            if (folderMap != null) {
                val folders = state.notes().folders(); state.notes().clearFolders()
                folders.forEach { state.notes().putFolder(FolderRecord(folderMap(it.path))) }
            }
            state.notes().all().filter { it.indexPending }.forEach { project(it) }
        }
    }
    @Serializable private data class ImportManifest(val folder: String, val paths: List<String>, val unresolved: List<String>)
    private fun importDirectory(token: String): File { require(UUID.fromString(token).toString() == token); return File(root, "imports/$token") }

    private data class IndexedLibraryNote(
        val record: NoteRecord,
        val raw: String,
        val cached: CachedAnalysis,
    ) {
        val analysis get() = cached.analysis
        val lowercase get() = cached.lowercase
    }

    private fun indexedLibraryNote(record: NoteRecord, projection: IndexRecord?): IndexedLibraryNote {
        val current = projection?.takeIf { it.version == record.version }
        val raw = current?.rawText.orEmpty()
        val key = "${record.id}:${record.version}"
        val cached = if (current != null) analysisCache.getOrPut(key) { CachedAnalysis(DocumentAnalyzer.analyze(raw), raw.lowercase(Locale.ROOT)) }
            else CachedAnalysis(DocumentAnalyzer.analyze(""), "")
        return IndexedLibraryNote(record, raw, cached)
    }

    private fun IndexedLibraryNote.asLibraryNote(words: List<String> = emptyList()): LibraryNote = LibraryNote(
        summary = record.summary(),
        pinned = record.pinned,
        created = record.created,
        modified = record.modified,
        deleted = record.deleted,
        tags = analysis.tags,
        title = analysis.title,
        matchOffset = words.firstOrNull()?.let { lowercase.indexOf(it).coerceAtLeast(0) } ?: 0,
        propertyTags = analysis.propertyTags,
        excerpt = excerpt(raw, analysis, words, cached),
        thumbnail = thumbnail(raw, analysis),
    )

    private fun excerpt(
        raw: String,
        analysis: com.elio.lifearchive.core.document.DocumentAnalysis,
        words: List<String>,
        cached: CachedAnalysis,
    ): String {
        var bodyStart = DocumentAnalyzer.yaml(raw)?.last?.plus(1) ?: if (raw.startsWith('\uFEFF')) 1 else 0
        val firstHeading = analysis.headings.firstOrNull()
        if (firstHeading != null && firstHeading.start >= bodyStart && raw.substring(bodyStart, firstHeading.start).isBlank()) {
            bodyStart = firstHeading.end
        }
        while (bodyStart < raw.length) {
            val lineEnd = raw.indexOf('\n', bodyStart).let { if (it < 0) raw.length else it }
            if (raw.substring(bodyStart, lineEnd).isNotBlank()) break
            bodyStart = (lineEnd + 1).coerceAtMost(raw.length)
        }
        val hit = words.firstNotNullOfOrNull { word -> raw.indexOf(word, bodyStart, ignoreCase = true).takeIf { it >= 0 } }
        var start = if (hit == null) bodyStart else (hit - 60).coerceAtLeast(bodyStart)
        var end = if (hit == null) (bodyStart + 320).coerceAtMost(raw.length) else (hit + 160).coerceAtMost(raw.length)
        // 子串边界不能拆开代理对，否则摘要中会出现损坏的字符。
        if (start > 0 && start < raw.length && raw[start].isLowSurrogate() && raw[start - 1].isHighSurrogate()) start--
        if (end > 0 && end < raw.length && raw[end].isLowSurrogate() && raw[end - 1].isHighSurrogate()) end++
        val (source, codeParts) = protectExcerptCode(raw, bodyStart, start, end, cached)
        var text = excerptWikiImage.replace(source, " ")
        text = excerptMarkdownImage.replace(text, " ")
        text = excerptWikiLink.replace(text) { it.groupValues[1].substringAfter('|', it.groupValues[1]) }
        text = excerptMarkdownLink.replace(text) { it.groupValues[1] }
        text = excerptHtml.replace(text, " ")
        text = excerptQuote.replace(text, "")
        text = excerptLinePrefix.replace(text, "")
        text = excerptFormatting.replace(text, "")
        codeParts.forEachIndexed { index, value -> text = text.replace("\uE000${index}\uE001", value) }
        text = excerptWhitespace.replace(text, " ").trim()
        val limited = text.take(160)
        return if (limited.lastOrNull()?.isHighSurrogate() == true) limited.dropLast(1) else limited
    }

    private data class ExcerptSegment(val start: Int, val end: Int, val code: Boolean)

    private fun CachedAnalysis.isHidden(raw: String, position: Int): Boolean {
        val ranges = hiddenRanges ?: run {
            // 仅在缩进或跨行代码存在歧义时解析一次；区间随既有分析缓存按版本失效。
            val masked = DocumentAnalyzer.masked(raw)
            val found = mutableListOf<IntRange>()
            var first = -1
            var last = -1
            raw.indices.forEach { index ->
                if (raw[index] != masked[index]) {
                    if (first < 0) first = index
                    last = index
                } else if (!raw[index].isWhitespace() && first >= 0) {
                    found += first..last
                    first = -1
                }
            }
            if (first >= 0) found += first..last
            found.also { hiddenRanges = it }
        }
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val range = ranges[middle]
            when {
                position < range.first -> high = middle - 1
                position > range.last -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    private fun protectExcerptCode(raw: String, bodyStart: Int, start: Int, end: Int, cached: CachedAnalysis): Pair<String, List<String>> {
        val segments = mutableListOf<ExcerptSegment>()
        fun overlaps(from: Int, to: Int) = segments.any { it.start < to && from < it.end }
        val fences = excerptFences.matcher(raw).region(bodyStart, end)
        var marker: String? = null
        var contentStart = 0
        while (fences.find()) {
            val found = requireNotNull(fences.group(1))
            val activeMarker = marker
            if (activeMarker == null) {
                marker = found
                val nextLine = raw.indexOf('\n', fences.end())
                contentStart = if (nextLine < 0 || nextLine >= end) end else nextLine + 1
                segments += ExcerptSegment(fences.start(), contentStart, false)
            } else if (found[0] == activeMarker[0] && found.length >= activeMarker.length && fences.group(2).orEmpty().isBlank()) {
                segments += ExcerptSegment(contentStart, fences.start(), true)
                segments += ExcerptSegment(fences.start(), fences.end(), false)
                marker = null
            }
        }
        if (marker != null) segments += ExcerptSegment(contentStart, end, true)

        val lineStart = (raw.lastIndexOf('\n', start - 1) + 1).coerceAtLeast(bodyStart)
        val indented = excerptIndented.matcher(raw).region(lineStart, end)
        while (indented.find()) {
            var firstContent = indented.start()
            while (firstContent < indented.end() && raw[firstContent].isWhitespace()) firstContent++
            if (firstContent < indented.end() && !overlaps(indented.start(), indented.end()) && cached.isHidden(raw, firstContent)) {
                segments += ExcerptSegment(indented.start(), indented.end(), true)
            }
        }

        // 行内代码可跨行；从当前段落起点而非摘要起点寻找包围标记。
        var paragraphStart = lineStart
        while (paragraphStart > bodyStart) {
            val previousEnd = paragraphStart - 1
            val previousStart = (raw.lastIndexOf('\n', previousEnd - 1) + 1).coerceAtLeast(bodyStart)
            if (raw.substring(previousStart, previousEnd).isBlank()) break
            paragraphStart = previousStart
        }
        val fixedSegments = segments.size
        val inline = excerptInlineCode.matcher(raw).region(paragraphStart, end)
        while (inline.find()) {
            if (overlaps(inline.start(), inline.end())) continue
            if (raw.substring(inline.start(2), inline.end(2)).contains('\n') && !cached.isHidden(raw, inline.start())) continue
            segments += ExcerptSegment(inline.start(), inline.start(2), false)
            segments += ExcerptSegment(inline.start(2), inline.end(2), true)
            segments += ExcerptSegment(inline.end(2), inline.end(), false)
        }

        // 摘要窗口可能截断行内代码，或代码内部含更短的反引号串。
        val backticks = excerptBackticks.matcher(raw).region(paragraphStart, end)
        var needsFallback = false
        while (backticks.find()) if (!overlaps(backticks.start(), backticks.end())) { needsFallback = true; break }
        if (needsFallback) {
            while (segments.size > fixedSegments) segments.removeAt(segments.lastIndex)
            backticks.reset().region(paragraphStart, end)
            var opening = -1
            var openingEnd = -1
            while (backticks.find()) {
                if (overlaps(backticks.start(), backticks.end()) || !cached.isHidden(raw, backticks.start())) continue
                if (opening < 0) {
                    opening = backticks.start()
                    openingEnd = backticks.end()
                } else if (backticks.end() - backticks.start() == openingEnd - opening) {
                    segments += ExcerptSegment(opening, openingEnd, false)
                    segments += ExcerptSegment(openingEnd, backticks.start(), true)
                    segments += ExcerptSegment(backticks.start(), backticks.end(), false)
                    opening = -1
                }
            }
            if (opening >= 0) {
                segments += ExcerptSegment(opening, openingEnd, false)
                segments += ExcerptSegment(openingEnd, end, true)
            }
        }

        val codeParts = mutableListOf<String>()
        val result = StringBuilder()
        var position = start
        segments.sortedBy { it.start }.forEach { segment ->
            val from = segment.start.coerceAtLeast(start)
            val to = segment.end.coerceAtMost(end)
            if (from >= to) return@forEach
            if (position < from) result.append(raw, position, from)
            if (segment.code) {
                val index = codeParts.size
                codeParts += raw.substring(from, to)
                result.append('\uE000').append(index).append('\uE001')
            }
            position = to
        }
        if (position < end) result.append(raw, position, end)
        return result.toString() to codeParts
    }

    private fun thumbnail(raw: String, analysis: com.elio.lifearchive.core.document.DocumentAnalysis): ThumbnailReference? {
        val yaml = DocumentAnalyzer.yaml(raw)
        return analysis.links.firstOrNull { link ->
            val path = DocumentAnalyzer.decode(link.target.substringBefore('#'))
            link.embed && (yaml == null || link.start !in yaml) &&
                !path.startsWith("//") && !path.startsWith("\\\\") && !linkScheme.containsMatchIn(path) && imageExtension.containsMatchIn(path)
        }?.let { ThumbnailReference(it.target, it.syntax) }
    }

    private inner class Library : LibraryRepository {
        override val progress: Flow<LibraryProgress?> = activityProgress
        override suspend fun query(query: NotesQuery): NoteResult<LibraryListing> = access {
            val projections = index.projections().all().associateBy { it.id }
            val records = state.notes().all()
            analysisCache.keys.retainAll(records.map { "${it.id}:${it.version}" }.toSet())
            val words = query.text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
            val all = records.map { record ->
                coroutineContext.ensureActive()
                indexedLibraryNote(record, projections[record.id])
            }
            val matches = all.filter { indexed ->
                val record = indexed.record; val analysis = indexed.analysis; val text = indexed.lowercase
                (record.deleted != null) == query.trash &&
                    (query.folder == null || record.path.startsWith(query.folder + "/")) &&
                    (query.tag == null || analysis.tags.any { it.equals(query.tag, true) || it.startsWith(query.tag + "/", true) }) &&
                    (words.isEmpty() || run {
                        // 列表刷新／排序不拼接全文；搜索时也不再复制大正文构造 haystack。
                        val metadata = (record.path + "\n" + analysis.title.orEmpty() + "\n" + analysis.aliases.joinToString(" ")).lowercase(Locale.ROOT)
                        words.all { it in metadata || it in text }
                    })
            }.map { it.asLibraryNote(words) }
            val order = when (query.sort) {
                NoteSort.MODIFIED_DESC -> compareByDescending<LibraryNote> { it.modified }
                NoteSort.MODIFIED_ASC -> compareBy { it.modified }
                NoteSort.CREATED_DESC -> compareByDescending { it.created }
                NoteSort.CREATED_ASC -> compareBy { it.created }
                NoteSort.NAME -> compareBy { it.summary.path.collisionKey }
            }
            val folders = (state.notes().folders().map { it.path } + records.filter { it.deleted == null }.flatMap { record ->
                val parts = record.path.split('/').dropLast(1); parts.indices.map { parts.take(it + 1).joinToString("/") }
            }).distinct().sorted()
            NoteResult.Success(LibraryListing(matches.sortedWith(compareByDescending<LibraryNote> { it.pinned }.then(order)), folders, all.flatMap { it.analysis.tags }.distinct().sorted(), records.count { it.indexPending }))
        }
        override suspend fun metadata(id: NoteId): NoteResult<LibraryNote> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            NoteResult.Success(indexedLibraryNote(record, index.projections().find(id.value)).asLibraryNote())
        }
        override suspend fun createFolder(path: String): NoteResult<Unit> = access {
            if (!LibraryPath.isValid(path)) return@access NoteResult.InvalidPath("非法笔记本名称")
            val existingFolders = state.notes().folders().map { it.path }
            val existing = existingFolders.map { "$it/.folder" } + state.notes().all().filter { it.deleted == null }.map { it.path } + attachmentPaths()
            if (existingFolders.any { it.equals(path, true) } || LibraryPath.conflicts("$path/.folder", existing)) return@access NoteResult.PathConflict
            state.notes().putFolder(FolderRecord(path)); publish(); NoteResult.Success(Unit)
        }
        override suspend fun relocateFolder(from: String, to: String): NoteResult<Unit> = access {
            require(LibraryPath.isValid(from) && LibraryPath.isValid(to) && !to.startsWith("$from/")) { "非法目标目录" }
            val paths = state.notes().all().filter { it.deleted == null }.map { it.path } + attachmentPaths()
            require(paths.any { it.startsWith("$from/") } || state.notes().folders().any { it.path == from || it.path.startsWith("$from/") }) { "笔记本不存在" }
            require(state.notes().folders().none { (it.path == to || it.path.startsWith("$to/")) && !it.path.startsWith("$from/") && it.path != from }) { "目标笔记本已存在" }
            fun moved(path: String) = if (path == from || path.startsWith("$from/")) to + path.removePrefix(from) else path
            relocateAll(paths.filter { it.startsWith("$from/") }.associateWith(::moved), ::moved)
        }
        override suspend fun trashFolder(path: String): NoteResult<Unit> = access {
            require(LibraryPath.isValid(path))
            val affected = state.notes().all().filter { it.deleted == null && it.path.startsWith("$path/") }
            affected.forEach { ensureNoDraft(it) }
            batch {
                affected.forEach { record ->
                    val deleted = record.copy(deleted = System.currentTimeMillis(), pathKey = "trash:${record.id}", version = UUID.randomUUID().toString())
                    val target = documentFile(deleted); check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    Files.move(documentFile(record).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    state.notes().put(deleted); project(deleted)
                }
                val folders = state.notes().folders().filterNot { it.path == path || it.path.startsWith("$path/") }
                state.notes().clearFolders(); folders.forEach { state.notes().putFolder(it) }
            }
        }
        override suspend fun setPinned(id: NoteId, pinned: Boolean): NoteResult<Unit> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            state.notes().put(record.copy(pinned = pinned)); publish(); NoteResult.Success(Unit)
        }
        override suspend fun setTags(id: NoteId, expected: DocumentVersion, tags: List<String>): NoteResult<Unit> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            if (record.deleted != null) return@access NoteResult.NotFound
            if (record.version != expected.value) return@access NoteResult.Conflict(DocumentVersion(record.version))
            ensureNoDraft(record)
                val bytes = documentFile(record).readBytes(); check(digest(bytes) == record.hash) { "正文校验失败" }
                val raw = RawDocument.fromUtf8(bytes)
            val changed = RawDocument(DocumentAnalyzer.withTags(raw.text, tags))
            val result = commit(record.copy(version = UUID.randomUUID().toString(), hash = digest(changed.utf8()), modified = System.currentTimeMillis()), changed, null)
            if (result is NoteResult.Committed) NoteResult.Success(Unit) else NoteResult.StorageFailure("标签保存失败")
        }
        override suspend fun relocate(id: NoteId, expected: DocumentVersion, path: String): NoteResult<Unit> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            if (record.deleted != null) return@access NoteResult.NotFound
            if (record.version != expected.value) return@access NoteResult.Conflict(DocumentVersion(record.version))
            if (!LibraryPath.isValid(path) || !path.endsWith(".md", true)) return@access NoteResult.InvalidPath("需要合法 .md 路径")
            relocateAll(mapOf(record.path to path))
        }
        override suspend fun trash(id: NoteId, expected: DocumentVersion): NoteResult<Unit> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            if (record.version != expected.value) return@access NoteResult.Conflict(DocumentVersion(record.version))
            ensureNoDraft(record)
            if (record.deleted != null) return@access NoteResult.Success(Unit)
            batch {
                val deleted = record.copy(deleted = System.currentTimeMillis(), pathKey = "trash:${record.id}", version = UUID.randomUUID().toString())
                val destination = documentFile(deleted); check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                Files.move(documentFile(record).toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                state.notes().put(deleted); project(deleted)
            }
        }
        override suspend fun restore(id: NoteId, path: String?): NoteResult<Unit> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            require(record.deleted != null) { "笔记不在回收站" }
            val target = path ?: record.path
            if (!LibraryPath.isValid(target) || !target.endsWith(".md", true)) return@access NoteResult.InvalidPath("非法恢复路径")
            val activePaths = state.notes().all().filter { it.deleted == null }.map { it.path } + attachmentPaths()
            if (LibraryPath.conflicts(target, activePaths + state.notes().folders().map { "${it.path}/.folder" })) return@access NoteResult.PathConflict
            val source = RawDocument.fromUtf8(documentFile(record).readBytes())
            val occupied = activePaths.any { it.equals(record.path, true) }
            val rewritten = RawDocument(rewrite(source.text, record.path, target, if (occupied) activePaths else activePaths + record.path, if (occupied) emptyMap() else mapOf(record.path to target)))
            val incoming = if (target == record.path || occupied) emptyList() else state.notes().all().filter { it.deleted == null }.mapNotNull { referring ->
                val original = RawDocument.fromUtf8(documentFile(referring).readBytes())
                val changed = RawDocument(rewrite(original.text, referring.path, referring.path, activePaths + record.path, mapOf(record.path to target)))
                if (changed == original) null else { ensureNoDraft(referring); referring to changed }
            }
            batch {
                val restored = record.copy(deleted = null, path = target, pathKey = LibraryPath(target).collisionKey, version = UUID.randomUUID().toString(), hash = digest(rewritten.utf8()), indexPending = true)
                val destination = documentFile(restored); check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                atomicWrite(destination, rewritten.utf8()); check(documentFile(record).delete())
                state.notes().put(restored); project(restored)
                incoming.forEach { (previous, document) ->
                    atomicWrite(documentFile(previous), document.utf8())
                    val changed = previous.copy(version = UUID.randomUUID().toString(), hash = digest(document.utf8()), modified = System.currentTimeMillis(), indexPending = true)
                    state.notes().put(changed); project(changed)
                }
            }
        }
        override suspend fun purge(id: NoteId): NoteResult<Unit> = access {
            val record = state.notes().find(id.value) ?: return@access NoteResult.NotFound
            require(record.deleted != null) { "只能永久删除回收站内笔记" }
            batch { check(documentFile(record).delete()); draftFile(id.value).takeIf { it.exists() }?.let { check(it.delete()) }; state.notes().remove(id.value); index.projections().remove(id.value) }
        }
        override suspend fun resolve(from: NoteId, target: String, syntax: LinkSyntax): NoteResult<ResolvedLink> = access {
            val source = state.notes().find(from.value) ?: return@access NoteResult.NotFound
            val records = state.notes().all().filter { it.deleted == null }
            val found = resolvePath(source.path, target.substringBefore('|'), records.map { it.path } + attachmentPaths(), syntax)
            if (found.size != 1) return@access NoteResult.Success(ResolvedLink(missing = found.isEmpty(), ambiguous = if (found.size > 1) found else emptyList()))
            val path = found.single(); val record = records.find { it.path == path }
            val fragment = DocumentAnalyzer.decode(target.substringAfter('#', ""))
            var location = com.elio.lifearchive.core.document.DocumentLocation()
            if (record != null && fragment.isNotEmpty()) {
                val analysis = DocumentAnalyzer.analyze(RawDocument.fromUtf8(documentFile(record).readBytes()).text)
                val anchor = if (fragment.startsWith('^')) analysis.blocks.find { it.name == fragment.drop(1) } else DocumentAnalyzer.heading(analysis, fragment)
                location = com.elio.lifearchive.core.document.DocumentLocation(heading = if (!fragment.startsWith('^')) fragment else null, block = if (fragment.startsWith('^')) fragment.drop(1) else null, offset = anchor?.start)
                if (anchor == null) return@access NoteResult.Success(ResolvedLink(note = NoteId(record.id), location = location, missing = true))
            }
            NoteResult.Success(ResolvedLink(record?.let { NoteId(it.id) }, if (record == null) LibraryPath(path) else null, location))
        }
        override suspend fun openResource(path: LibraryPath, offset: Long, length: Long?): NoteResult<ResourceStream> = access {
            val file = safeFile(attachments, path)
            if (!file.isFile) return@access NoteResult.NotFound
            require(offset >= 0 && offset <= file.length() && (length == null || length >= 0))
            val input = file.inputStream(); try {
                var remaining = offset; while (remaining > 0) { val skipped = input.skip(remaining); check(skipped > 0); remaining -= skipped }
                val bounded = object : java.io.FilterInputStream(input) {
                    var availableBytes = minOf(length ?: Long.MAX_VALUE, file.length() - offset)
                    override fun read(): Int { if (availableBytes <= 0) return -1; val byte = super.read(); if (byte >= 0) availableBytes--; return byte }
                    override fun read(b: ByteArray, off: Int, len: Int): Int { if (availableBytes <= 0) return -1; val n = `in`.read(b, off, minOf(len.toLong(), availableBytes).toInt()); if (n > 0) availableBytes -= n; return n }
                }
                val extension = file.extension.lowercase(Locale.ROOT)
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: if (extension == "svg") "image/svg+xml" else "application/octet-stream"
                NoteResult.Success(ResourceStream(ResourceInfo(path, file.length(), mime), bounded))
            } catch (e: Exception) { input.close(); throw e }
        }
        override suspend fun prepareImport(entries: List<ImportEntry>, folder: String): NoteResult<PreparedImport> = access {
            require(entries.isNotEmpty() && entries.size <= 20000 && LibraryPath.isValid(folder)) { "请选择有效资料与目标笔记本" }
            val token = UUID.randomUUID().toString(); val directory = importDirectory(token).also { check(it.mkdirs()) }
            try {
                val paths = mutableListOf<String>(); var bytes = 0L
                entries.forEach { entry ->
                    require(LibraryPath.isValid(entry.path) && !LibraryPath.conflicts(entry.path, paths)) { "导入路径冲突：${entry.path}" }; paths += entry.path
                    val file = safeFile(directory, LibraryPath("files/${entry.path}")); check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    entry.open().use { input -> file.outputStream().use { output -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= 2L * 1024 * 1024 * 1024) { "导入超过 2 GiB" }; output.write(buffer, 0, n) } } }
                    if (entry.path.endsWith(".md", true)) RawDocument.fromUtf8(file.readBytes())
                }
                val unresolved = paths.filter { it.endsWith(".md", true) }.flatMap { path -> DocumentAnalyzer.analyze(RawDocument.fromUtf8(safeFile(directory, LibraryPath("files/$path")).readBytes()).text).links.filter { resolvePath(path, it.target, paths, it.syntax).isEmpty() && !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(it.target) }.map { it.target } }.distinct()
                atomicWrite(File(directory, "manifest.json"), json.encodeToString(ImportManifest(folder, paths, unresolved)).toByteArray())
                NoteResult.Success(PreparedImport(token, folder, paths.count { it.endsWith(".md", true) }, paths.count { !it.endsWith(".md", true) }, unresolved))
            } catch (e: Exception) { directory.deleteRecursively(); throw e }
        }
        override suspend fun commitImport(prepared: PreparedImport): NoteResult<Unit> = access {
            val directory = importDirectory(prepared.token)
            val manifest = json.decodeFromString<ImportManifest>(File(directory, "manifest.json").readText())
            require(manifest.folder == prepared.folder && LibraryPath.isValid(manifest.folder))
            val existing = state.notes().all().filter { it.deleted == null }.map { it.path } + attachmentPaths() + state.notes().folders().map { it.path }
            require(existing.none { it.equals(manifest.folder, true) || it.startsWith(manifest.folder + "/", true) }) { "目标目录已存在，请换一个导入目录" }
            val mapping = manifest.paths.associateWith { "${manifest.folder}/$it" }
            validateNamespace(state.notes().all().filter { it.deleted == null }.map { it.path } + attachmentPaths() + mapping.values, state.notes().folders().map { it.path } + manifest.folder)
            val result = batch {
                manifest.paths.forEachIndexed { index, path ->
                    activityProgress.value = LibraryProgress("导入资料", index, manifest.paths.size)
                    val target = mapping.getValue(path)
                    if (path.endsWith(".md", true)) {
                        val bytes = safeFile(directory, LibraryPath("files/$path")).readBytes()
                        val document = RawDocument(rewrite(RawDocument.fromUtf8(bytes).text, path, target, manifest.paths, mapping))
                        val now = System.currentTimeMillis()
                        val record = NoteRecord(UUID.randomUUID().toString(), target, LibraryPath(target).collisionKey, UUID.randomUUID().toString(), digest(document.utf8()), created = now, modified = now)
                        atomicWrite(documentFile(record), document.utf8()); state.notes().put(record); project(record)
                    } else {
                        val targetFile = safeFile(attachments, LibraryPath(target)); check(targetFile.parentFile!!.isDirectory || targetFile.parentFile!!.mkdirs())
                        safeFile(directory, LibraryPath("files/$path")).inputStream().use { input -> FileOutputStream(targetFile).use { output -> input.copyTo(output, 65536); output.fd.sync() } }
                    }
                    fault("batch-import")
                }
                state.notes().putFolder(FolderRecord(manifest.folder))
            }
            activityProgress.value = null
            directory.deleteRecursively(); result
        }
        override suspend fun discardImport(prepared: PreparedImport) { gate.access { importDirectory(prepared.token).deleteRecursively() } }
        override suspend fun export(output: OutputStream, ids: Set<NoteId>?): NoteResult<Unit> = access {
            val records = state.notes().all().filter { it.deleted == null }; val resources = attachmentPaths(); val paths = records.map { it.path } + resources
            validateNamespace(paths, state.notes().folders().map { it.path })
            require(ids == null || (ids.isNotEmpty() && ids.all { id -> records.any { it.id == id.value } })) { "导出的笔记已变化或不存在" }
            val selected = records.filter { ids == null || NoteId(it.id) in ids }.map { it.path }.toMutableSet()
            val attached = if (ids == null) resources.toMutableSet() else mutableSetOf()
            val visited = mutableSetOf<String>()
            while (true) {
                val path = selected.firstOrNull { it !in visited } ?: break; visited += path
                val record = records.first { it.path == path }
                DocumentAnalyzer.analyze(RawDocument.fromUtf8(documentFile(record).readBytes()).text).links.forEach { link ->
                    val resolved = resolvePath(path, link.target, paths, link.syntax)
                    if (resolved.size == 1) { if (records.any { it.path == resolved[0] }) selected += resolved[0] else attached += resolved[0] }
                }
            }
            ZipOutputStream(output).use { zip ->
                state.notes().folders().filter { folder -> ids == null || selected.any { it.startsWith(folder.path + "/") } }.forEach { zip.putNextEntry(ZipEntry(it.path + "/")); zip.closeEntry() }
                selected.forEach { path -> zip.putNextEntry(ZipEntry(path)); documentFile(records.first { it.path == path }).inputStream().use { it.copyTo(zip, 65536) }; zip.closeEntry() }
                attached.forEach { path -> zip.putNextEntry(ZipEntry(path)); safeFile(attachments, LibraryPath(path)).inputStream().use { it.copyTo(zip, 65536) }; zip.closeEntry() }
            }
            NoteResult.Success(Unit)
        }
    }

    private inner class Participant : BackupParticipant {
        override val id = "notes"

        override suspend fun snapshot(directory: File) {
            recover()
            val records = state.notes().all()
            atomicWrite(File(directory, "state.json"), json.encodeToString(SnapshotState(format = 2, notes = records, folders = state.notes().folders())).toByteArray())
            val docDest = File(directory, "documents").also { check(it.mkdirs()) }
            val draftDest = File(directory, "drafts").also { check(it.mkdirs()) }
            // 只导出已登记正文和草稿；提交前退出遗留的暂存文件不是用户内容。
            records.forEach { record ->
                check(digest(documentFile(record).readBytes()) == record.hash) { "正文校验失败：${record.path}" }
                val path = LibraryPath(record.path)
                val destination = if (record.deleted == null) safeFile(docDest, path) else File(directory, "trash/${record.id}.md")
                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                documentFile(record).copyTo(destination)
                val draft = draftFile(record.id)
                if (draft.isFile) draft.copyTo(File(draftDest, draft.name))
            }
            val attachmentDest = File(directory, "attachments").also { check(it.mkdirs()) }
            if (attachments.exists()) attachments.copyRecursively(attachmentDest, overwrite = true)
            validate(directory)
        }

        override suspend fun validate(directory: File) {
            val stateFile = File(directory, "state.json")
            require(stateFile.length() in 1..(16 * 1024 * 1024))
            val content = json.decodeFromString<SnapshotState>(stateFile.readText())
            require(content.format in 1..2)
            content.folders.forEach { require(LibraryPath.isValid(it.path)) }
            require(content.notes.map { it.id }.distinct().size == content.notes.size)
            require(content.notes.map { it.pathKey }.distinct().size == content.notes.size)
            val checkedPaths = mutableListOf<String>()
            content.notes.forEach { record ->
                require(UUID.fromString(record.id).toString() == record.id)
                require(UUID.fromString(record.version).toString() == record.version)
                val path = LibraryPath(record.path)
                if (record.deleted == null) { require(!LibraryPath.conflicts(path.value, checkedPaths)); checkedPaths.add(path.value) }
                require(path.value.endsWith(".md", true) && record.pathKey == if (record.deleted == null) path.collisionKey else "trash:${record.id}")
                val bytes = (if (record.deleted == null) safeFile(File(directory, "documents"), path) else File(directory, "trash/${record.id}.md")).readBytes()
                require(digest(bytes) == record.hash)
                RawDocument.fromUtf8(bytes)
            }
            val expectedDocuments = content.notes.filter { it.deleted == null }.map { it.path }.toSet()
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
            validateNamespace(checkedPaths + attachmentPaths, content.folders.map { it.path })
            require(File(directory, "trash").walkTopDown().filter { it.isFile }.map { it.name }.toSet() == content.notes.filter { it.deleted != null }.map { "${it.id}.md" }.toSet())
            require(directory.list().orEmpty().all { it in setOf("state.json", "documents", "drafts", "attachments", "trash") })
        }

        override suspend fun restore(directory: File) {
            validate(directory)
            // 引擎已持久保存旧快照；中途退出后会重新完整恢复旧快照。
            for (name in listOf("documents", "attachments", "drafts", "trash")) {
                val target = File(root, name)
                check(!target.exists() || target.deleteRecursively())
                check(target.mkdirs())
                val source = File(directory, name)
                if (source.exists()) source.copyRecursively(target, overwrite = true)
            }
            val content = json.decodeFromString<SnapshotState>(File(directory, "state.json").readText())
            state.notes().replace(content.notes)
            analysisCache.clear()
            state.notes().clearFolders()
            content.folders.forEach { state.notes().putFolder(it) }
            if (journal.exists()) check(journal.delete())
            try { index.projections().clear() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 状态已标记待重建。 */ }
            content.notes.forEach { project(it.copy(indexPending = true)) }
            publish()
        }
    }
}
