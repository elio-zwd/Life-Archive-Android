package com.elio.lifearchive.data.notes

import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class OrganizationStorageTest {
    @Test fun versionThreeDatabaseAndBackupMigrateWithoutInventingContentTimes() = runBlocking {
        val root = temp.newFolder()
        val id = java.util.UUID.randomUUID().toString(); val version = java.util.UUID.randomUUID().toString()
        val raw = "\uFEFF# 历史原文\r\n"
        File(root, "documents").mkdirs(); File(root, "documents/历史.md").writeText(raw)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        val schemaFile = listOf(File("schemas/com.elio.lifearchive.data.notes.StateDatabase/3.json"), File("data/notes/schemas/com.elio.lifearchive.data.notes.StateDatabase/3.json")).first { it.isFile }
        val schema = org.json.JSONObject(schemaFile.readText()).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(File(root, "state.db"), null).use { database ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indexes = entity.optJSONArray("indices")
                if (indexes != null) for (j in 0 until indexes.length()) database.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) database.execSQL(setup.getString(i))
            database.execSQL("INSERT INTO notes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(id, "历史.md", "历史.md", version, hash, 1, 1, 23, 1000L, 2000L, null, "lake", null))
            database.version = 3
        }
        open(root).use { storage ->
            storage.repository.initialize().get()
            val note = storage.library.metadata(NoteId(id)).get()
            assertTrue(note.pinned); assertEquals(NoteCover.Preset(CoverPreset.LAKE), note.cover)
            assertEquals(23, storage.repository.readReadingOffset(NoteId(id)).get())
            assertEquals(raw, storage.current(NoteId(id)).document.text)
            assertEquals(ContentTimes(), note.contentTimes)
            val snapshot = temp.newFolder(); storage.backupParticipant.snapshot(snapshot)
            val stateFile = File(snapshot, "state.json"); val state = org.json.JSONObject(stateFile.readText())
            state.put("format", 3); state.remove("topics"); state.getJSONArray("notes").getJSONObject(0).remove("timeUncertain")
            stateFile.writeText(state.toString())
            storage.backupParticipant.restore(snapshot)
            assertEquals(ContentTimes(), storage.library.metadata(NoteId(id)).get().contentTimes)
        }
    }
    @get:Rule val temp = TemporaryFolder()
    private fun open(root: File) = NotesStorage.open(ApplicationProvider.getApplicationContext(), root, StorageGate())
    private fun <T> NoteResult<T>.get(): T = when (this) { is NoteResult.Success -> value; is NoteResult.Committed -> value; else -> error(toString()) }
    private suspend fun NotesStorage.new(path: String, raw: String = "正文") = repository.createManaged(path, RawDocument(raw)).get()
    private suspend fun NotesStorage.current(id: NoteId) = repository.read(id).get()
    private fun change(note: NoteSnapshot, topic: String? = "职业发展", kind: NoteKind? = NoteKind.PERSONAL, tags: List<String>? = null, times: ContentTimes? = null) = OrganizationChange(note.summary.id, note.summary.version, topic, kind, tags, times)

    @Test fun authoredTimesAreIndependentOfMaintenanceAndUnknownHistoryStaysUnknown() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val first = storage.new("待整理/个人笔记/原文.md", "\uFEFF---\r\nunknown: 保留\r\n---\r\n正文\r\n")
            assertNotNull(NoteIdentity.read(first.document.text)); val initial = NoteTimes.read(first.document.text)
            assertNotNull(initial.created); assertEquals(initial.created, initial.updated)
            val tagsOnly = storage.repository.saveContent(first.summary.id, first.summary.version, RawDocument(DocumentAnalyzer.withTags(first.document.text, listOf("职业发展/面试")))).get()
            assertEquals(initial, NoteTimes.read(tagsOnly.document.text))
            val edited = storage.repository.saveContent(first.summary.id, tagsOnly.summary.version, RawDocument(tagsOnly.document.text + "新增内容\r\n")).get()
            assertEquals(initial.created, NoteTimes.read(edited.document.text).created)
            assertNotEquals(initial.updated, NoteTimes.read(edited.document.text).updated)
            val history = storage.repository.createManaged("待整理/历史.md", RawDocument("---\ndate: 2020-01-01\n---\n历史"), false).get()
            assertEquals(ContentTimes(), NoteTimes.read(history.document.text))
            assertTrue(storage.library.metadata(history.summary.id).get().created > 0)
            val expected = storage.sync.inventory().get().files.single { it.key == "n:${NoteIdentity.read(edited.document.text)}" }
            val externalBytes = (edited.document.text + "外部编辑").toByteArray()
            val externalHash = java.security.MessageDigest.getInstance("SHA-256").digest(externalBytes).joinToString("") { "%02x".format(it) }
            storage.sync.apply(expected, expected.copy(hash = externalHash, size = externalBytes.size.toLong()), externalBytes.inputStream()).get()
            val uncertain = storage.library.metadata(first.summary.id).get()
            assertTrue(uncertain.timeUncertain)
            val confirmation = change(storage.current(first.summary.id), topic = null, times = uncertain.contentTimes)
            storage.library.applyOrganization(storage.library.previewOrganization(listOf(confirmation)).get()).get()
            assertFalse(storage.library.metadata(first.summary.id).get().timeUncertain)
            assertEquals(uncertain.contentTimes, storage.library.metadata(first.summary.id).get().contentTimes)
        }
    }
    @Test fun invalidHistoricalTimeCanBeExplicitlyRepairedInSourceWithoutInventingCreation() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val note = storage.repository.create("历史.md", RawDocument("---\nlife_archive_updated_at: 无效\nunknown: keep\n---\n正文")).get()
            assertTrue(storage.library.metadata(note.summary.id).get().timeUncertain)
            val repaired = storage.repository.saveContent(note.summary.id, note.summary.version, RawDocument(note.document.text.replace("无效", "2026-11-01"))).get()
            assertEquals(ContentTimes(updated = "2026-11-01"), NoteTimes.read(repaired.document.text))
            assertTrue(repaired.document.text.contains("unknown: keep"))
        }
    }
    @Test fun previewIsReadOnlyAndBatchKeepsLinksIdentityAndMobileState() = runBlocking {
        val root = temp.newFolder()
        open(root).use { storage ->
            storage.repository.importAttachment("图.png", byteArrayOf(1,2,3).inputStream()).get()
            val note = storage.new("原文.md", "![图](图.png)\n[B](B.md)")
            storage.new("B.md"); val referring = storage.new("引用.md", "[原文](原文.md)")
            storage.library.setPinned(note.summary.id, true).get(); storage.library.setCover(note.summary.id, NoteCover.Preset(CoverPreset.LAKE)).get(); storage.repository.updateReadingOffset(note.summary.id, 27).get()
            val preview = storage.library.previewOrganization(listOf(change(note, tags = listOf("职业发展/面试/自我介绍", "沟通表达/表达")))).get()
            assertEquals(note.document, storage.current(note.summary.id).document)
            assertTrue(preview.items.single().affectedLinks.contains("引用.md"))
            assertEquals("COMPLETED", storage.library.applyOrganization(preview).get().outcomes.single().status)
            val moved = storage.current(note.summary.id)
            assertEquals("职业发展/个人笔记/原文.md", moved.summary.path.value)
            assertEquals(NoteIdentity.read(note.document.text), NoteIdentity.read(moved.document.text))
            assertEquals(NoteTimes.read(note.document.text), NoteTimes.read(moved.document.text))
            val imageLink = DocumentAnalyzer.analyze(moved.document.text).links.first { it.embed }
            assertEquals(LibraryPath("图.png"), storage.library.resolve(moved.summary.id, imageLink.target, imageLink.syntax).get().attachment)
            val backLink = DocumentAnalyzer.analyze(storage.current(referring.summary.id).document.text).links.single()
            assertEquals(moved.summary.id, storage.library.resolve(referring.summary.id, backLink.target, backLink.syntax).get().note)
            val metadata = storage.library.metadata(note.summary.id).get()
            assertTrue(metadata.pinned); assertEquals(NoteCover.Preset(CoverPreset.LAKE), metadata.cover)
            assertEquals(27, storage.repository.readReadingOffset(note.summary.id).get())
            assertEquals(1, storage.library.query(NotesQuery(selectedTags = listOf("职业发展/面试", "沟通表达"))).get().notes.size)
            assertEquals(1, storage.library.query(NotesQuery(selectedTags = listOf("不存在", "沟通表达"), tagMatch = TagMatch.ANY)).get().notes.size)
            assertEquals(0, storage.library.query(NotesQuery(selectedTags = listOf("不存在", "沟通表达"))).get().notes.size)
        }
    }
    @Test fun staleReferencesDraftsAndCollisionsSkipOnlyAffectedItems() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val a = storage.new("甲/同名.md"); val b = storage.new("乙/同名.md"); val independent = storage.new("独立.md")
            val duplicate = storage.library.previewOrganization(listOf(change(a), change(b), change(independent))).get()
            val outcomes = storage.library.applyOrganization(duplicate).get().outcomes
            assertEquals(2, outcomes.count { it.status == "SKIPPED" }); assertEquals(1, outcomes.count { it.status == "COMPLETED" })
            val ref = storage.new("引用.md", "[甲](甲/同名.md)")
            val preview = storage.library.previewOrganization(listOf(change(a))).get()
            storage.repository.save(ref.summary.id, ref.summary.version, RawDocument(ref.document.text + "变化")).get()
            assertEquals("SKIPPED", storage.library.applyOrganization(preview).get().outcomes.single().status)
            storage.repository.writeDraft(Draft(a.summary.id, a.summary.version, 1, RawDocument("未保存"))).get()
            assertNotNull(storage.library.previewOrganization(listOf(change(a))).get().items.single().issue)
        }
    }
    @Test fun cancellationRecoversWholeBatchAndDoesNotDuplicateNotes() = runBlocking {
        val root = temp.newFolder(); var storage = open(root)
        val a = storage.new("甲.md"); val b = storage.new("乙.md")
        val preview = storage.library.previewOrganization(listOf(change(a), change(b))).get()
        storage.fault = { if (it == "batch-note") throw CancellationException("模拟中断") }
        assertTrue(runCatching { storage.library.applyOrganization(preview) }.isFailure)
        storage.close(); storage = open(root)
        storage.use {
            it.repository.initialize().get()
            assertEquals("甲.md", it.current(a.summary.id).summary.path.value)
            assertEquals("乙.md", it.current(b.summary.id).summary.path.value)
            assertEquals(2, it.library.query().get().notes.size)
        }
    }
    @Test fun diskFailureRollsBackAndConfirmedTimesKeepDatePrecision() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val note = storage.repository.create("历史.md", RawDocument("---\ndate: 2000-01-01\n---\n正文")).get()
            val preview = storage.library.previewOrganization(listOf(change(note, times = ContentTimes("2020-01-01", "2020-01-02")))).get()
            storage.fault = { if (it == "batch-note") error("模拟磁盘失败") }
            assertEquals("FAILED", storage.library.applyOrganization(preview).get().outcomes.single().status)
            storage.fault = {}
            assertEquals(note.document, storage.current(note.summary.id).document)
            assertEquals("COMPLETED", storage.library.applyOrganization(preview).get().outcomes.single().status)
            val updated = storage.current(note.summary.id)
            assertEquals(ContentTimes("2020-01-01", "2020-01-02"), NoteTimes.read(updated.document.text))
            assertTrue(updated.document.text.contains("date: 2000-01-01"))
        }
    }
    @Test fun topicAndTagMergesHavePreviewAndRoundtripThroughBackup() = runBlocking {
        open(temp.newFolder()).use { storage ->
            storage.repository.initialize().get()
            val note = storage.new("职业发展/视频摘记/甲.md", "---\ntags: [职业发展/面试]\n---\n#职业发展/面试\n`#职业发展/面试`")
            val preview = storage.library.previewTaxonomy(TaxonomyChange("职业发展", "职业成长", true)).get()
            assertEquals("职业发展/视频摘记/甲.md", storage.current(note.summary.id).summary.path.value)
            storage.library.applyTaxonomy(preview).get()
            assertEquals("职业成长/视频摘记/甲.md", storage.current(note.summary.id).summary.path.value)
            assertFalse("职业发展" in storage.library.query().get().topics)
            val tags = storage.library.previewTaxonomy(TaxonomyChange("职业成长/面试", "职业成长/求职/面试", false)).get()
            storage.library.applyTaxonomy(tags).get()
            val raw = storage.current(note.summary.id).document.text
            assertTrue(raw.contains("#职业成长/求职/面试")); assertTrue(raw.contains("`#职业发展/面试`"))
            assertEquals(NoteTimes.read(note.document.text), NoteTimes.read(raw))
            val snapshot = temp.newFolder(); storage.backupParticipant.snapshot(snapshot)
            storage.library.createFolder("临时主题/个人笔记").get()
            storage.backupParticipant.restore(snapshot)
            assertFalse("临时主题" in storage.library.query().get().topics)
        }
    }
    @Test fun fiveHundredNotesCanBePreviewedAndCommittedWithoutContentTimeChurn() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val notes = (0 until 500).map { storage.new("待整理/笔记$it.md", "合成笔记 $it") }
            val start = System.nanoTime()
            val preview = storage.library.previewOrganization(notes.map { change(it) }).get()
            val result = storage.library.applyOrganization(preview).get()
            assertEquals(500, result.outcomes.count { it.status == "COMPLETED" })
            assertEquals(500, storage.library.query(NotesQuery(topic = "职业发展")).get().notes.size)
            notes.forEach { assertEquals(NoteTimes.read(it.document.text), NoteTimes.read(storage.current(it.summary.id).document.text)) }
            println("organization-500-notes-ms=" + (System.nanoTime() - start) / 1_000_000)
        }
    }
}
