package com.elio.lifearchive.data.sync

import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.backup.*
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.data.notes.NotesStorage
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.domain.sync.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SyncIntegrationTest {
    @Test fun organizationAndControlledPcEditingRoundtripWithRealStoresAndHttp() = runBlocking {
        val first = notes.repository.createManaged("待整理/个人笔记/整理往返.md", RawDocument("原文")).get()
        notes.library.setPinned(first.summary.id, true).get()
        notes.repository.updateReadingOffset(first.summary.id, 19).get()
        sync.synchronize(); assertCompleted()
        val change = OrganizationChange(first.summary.id, first.summary.version, "职业发展", NoteKind.PERSONAL, listOf("职业发展/面试/自我介绍"))
        val preview = notes.library.previewOrganization(listOf(change)).get()
        assertEquals("COMPLETED", notes.library.applyOrganization(preview).get().outcomes.single().status)
        sync.synchronize(); assertCompleted()
        val relative = "职业发展/个人笔记/整理往返.md"
        val file = File(vault, relative)
        assertTrue(file.isFile); assertFalse(File(vault, "待整理/个人笔记/整理往返.md").exists())
        assertEquals(NoteTimes.read(first.document.text), NoteTimes.read(file.readText()))
        val pcRoot = File(requireNotNull(System.getProperty("lifeArchive.pcRoot")))
        fun tool(script: String) {
            val process = ProcessBuilder("pwsh.exe", "-NoProfile", "-File", File(pcRoot, "tools/$script").absolutePath, "-Root", vault.absolutePath, "-TaskId", "真实往返", "-Paths", relative).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        tool("Backup-NotesBeforeEdit.ps1"); file.appendText("\n电脑 AI 补充")
        tool("Complete-NotesAiEdit.ps1")
        val complete = file.readText()
        tool("Complete-NotesAiEdit.ps1"); assertEquals(complete, file.readText())
        sync.synchronize(); assertCompleted()
        val current = notes.repository.read(first.summary.id).get()
        assertEquals(complete, current.document.text)
        assertEquals(NoteIdentity.read(first.document.text), NoteIdentity.read(current.document.text))
        val metadata = notes.library.metadata(first.summary.id).get()
        assertTrue(metadata.pinned); assertFalse(metadata.timeUncertain)
        assertEquals(19, notes.repository.readReadingOffset(first.summary.id).get())
        sync.synchronize(); assertCompleted(); assertEquals(0L, sync.state.value.transferredBytes)
    }
    @get:Rule val temp = TemporaryFolder()
    private lateinit var process: Process
    private lateinit var notes: NotesStorage
    private lateinit var sync: SyncRuntime
    private lateinit var vault: File
    private lateinit var address: String
    private lateinit var code: String
    private lateinit var root: File
    private lateinit var backup: BackupEngine
    private val credentials = object : DeviceCredentials {
        var token: String? = null
        override fun read() = token
        override fun write(token: String) { this.token = token }
        override fun clear() { token = null }
    }
    private fun <T> NoteResult<T>.get(): T = when (this) { is NoteResult.Success -> value; is NoteResult.Committed -> value; else -> error(toString()) }
    @Before fun start() = runBlocking {
        root = temp.newFolder("android")
        val pcRoot = File(requireNotNull(System.getProperty("lifeArchive.pcRoot")))
        check(File(pcRoot, "test/fixtures/simple-sync-server.mjs").isFile) { "需要 PC 项目或 LIFE_ARCHIVE_PC_ROOT" }
        val evidence = temp.newFolder("pc")
        process = ProcessBuilder("node", "test/fixtures/simple-sync-server.mjs", evidence.absolutePath).directory(pcRoot).redirectError(File(evidence, "stderr.log")).start()
        val reader = process.inputStream.bufferedReader()
        val executor = Executors.newSingleThreadExecutor()
        val ready = try { executor.submit<String> { reader.readLine() ?: error(File(evidence, "stderr.log").readText()) }.get(30, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        val json = Json.parseToJsonElement(ready).jsonObject
        address = json.getValue("address").jsonPrimitive.content; code = json.getValue("code").jsonPrimitive.content
        vault = File(json.getValue("root").jsonPrimitive.content)
        val gate = StorageGate()
        notes = NotesStorage.open(ApplicationProvider.getApplicationContext(), File(root, "notes"), gate)
        sync = SyncRuntime(File(root, "sync"), gate, notes.sync, credentials)
        backup = BackupEngine(File(root, "backup"), gate, listOf(notes.backupParticipant, notes.syncTrashBackupParticipant, sync.backupParticipant))
        backup.initialize()
        notes.repository.initialize().get()
        sync.connect(address, code)
        assertTrue(sync.state.value.toString(), sync.state.value.paired)
    }
    @After fun stop() {
        if (::notes.isInitialized) notes.close()
        if (::process.isInitialized) { process.destroy(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly() }
    }
    private fun assertCompleted() { assertTrue(sync.state.value.message, sync.state.value.message.startsWith("本轮完成")) }
    @Test fun connectionTestDoesNotAddIdsOrModifyAnyDocument() = runBlocking {
        val original = "\uFEFF---\r\nunknown: true\r\n---\r\n原文\r\n"
        val file = File(vault, "待同步.md"); file.writeText(original)
        val local = notes.repository.create("手机.md", RawDocument("saved")).get()
        sync.testConnection()
        assertTrue(sync.state.value.message, sync.state.value.message.startsWith("连接正常"))
        assertEquals(original, file.readText())
        assertEquals("saved", notes.repository.read(local.summary.id).get().document.text)
    }
    @Test fun realHttpRoundtripPreservesIdentityAndMobileStateOnPcRename() = runBlocking {
        val created = notes.repository.create("中文/原名.md", RawDocument("\uFEFF---\r\nunknown: [a, b]\r\n---\r\n# 标题\r\n")).get()
        notes.library.setPinned(created.summary.id, true).get()
        notes.library.setCover(created.summary.id, NoteCover.Preset(CoverPreset.LAKE)).get()
        notes.repository.updateReadingOffset(created.summary.id, 24).get()
        notes.repository.importAttachment("中文/附件.bin", ByteArray(4096) { it.toByte() }.inputStream()).get()
        sync.synchronize(); assertCompleted()
        val exported = File(vault, "中文/原名.md")
        assertTrue(exported.isFile)
        assertEquals(created.summary.id.value, NoteIdentity.read(exported.readText()))
        assertTrue(exported.readText().contains("unknown: [a, b]\r\n"))
        sync.synchronize(); assertCompleted(); assertEquals(0L, sync.state.value.transferredBytes)
        val renamed = File(vault, "改名/记录.md"); renamed.parentFile!!.mkdirs(); exported.renameTo(renamed)
        renamed.appendText("电脑改名并编辑\r\n")
        sync.synchronize(); assertCompleted()
        val metadata = notes.library.metadata(created.summary.id).get()
        assertEquals("改名/记录.md", metadata.summary.path.value)
        assertTrue(metadata.pinned); assertEquals(NoteCover.Preset(CoverPreset.LAKE), metadata.cover)
        assertEquals(24, notes.repository.readReadingOffset(created.summary.id).get())
        val current = notes.repository.read(created.summary.id).get()
        assertTrue(current.document.text.endsWith("电脑改名并编辑\r\n"))
        notes.library.relocate(created.summary.id, current.summary.version, "手机移动/记录.md").get()
        sync.synchronize(); assertCompleted()
        assertTrue(File(vault, "手机移动/记录.md").isFile); assertFalse(renamed.exists())
        assertEquals(1, notes.library.query().get().notes.size)
    }
    @Test fun conflictChoicesSkipAndBothWinnersAndDeletionTrash() = runBlocking {
        val created = notes.repository.create("笔记.md", RawDocument("base")).get()
        sync.synchronize(); assertCompleted()
        val local = notes.repository.read(created.summary.id).get()
        notes.repository.save(local.summary.id, local.summary.version, RawDocument(local.document.text + "phone")).get()
        File(vault, "笔记.md").appendText("computer")
        sync.synchronize(); assertEquals(1, sync.state.value.conflicts.size)
        val key = sync.state.value.conflicts.single().key
        sync.synchronize(mapOf(key to SyncChoice.SKIP)); assertTrue(File(vault, "笔记.md").readText().endsWith("computer"))
        sync.synchronize(mapOf(key to SyncChoice.COMPUTER)); assertCompleted()
        assertTrue(notes.repository.read(created.summary.id).get().document.text.endsWith("computer"))
        val next = notes.repository.read(created.summary.id).get()
        notes.repository.save(next.summary.id, next.summary.version, RawDocument(next.document.text + "-phone2")).get()
        File(vault, "笔记.md").appendText("-computer2")
        sync.synchronize(); sync.synchronize(mapOf(key to SyncChoice.PHONE)); assertCompleted()
        assertTrue(File(vault, "笔记.md").readText().endsWith("-phone2"))
        File(vault, "笔记.md").delete()
        sync.synchronize(); assertCompleted()
        assertTrue(notes.library.query().get().notes.isEmpty())
        assertEquals(created.summary.id, notes.library.query(NotesQuery(trash = true)).get().notes.single().summary.id)
        sync.synchronize(); assertTrue(notes.library.query().get().notes.isEmpty())
    }
    @Test fun attachmentsAreStreamedDeletedAndRecoverable() = runBlocking {
        val bytes = ByteArray(1024 * 1024) { it.toByte() }
        notes.repository.importAttachment("资料/图片.bin", bytes.inputStream()).get()
        sync.synchronize(); assertCompleted()
        assertArrayEquals(bytes, File(vault, "资料/图片.bin").readBytes())
        File(vault, "资料/图片.bin").delete()
        sync.synchronize(); assertCompleted()
        assertEquals(1, sync.state.value.trash.size)
        sync.restoreAttachment(sync.state.value.trash.single().token)
        sync.synchronize(); assertCompleted()
        assertArrayEquals(bytes, File(vault, "资料/图片.bin").readBytes())
    }
    @Test fun invalidOrDuplicatePcIdDoesNotDeleteMobileOriginal() = runBlocking {
        val created = notes.repository.create("原件.md", RawDocument("original")).get()
        sync.synchronize(); assertCompleted()
        File(vault, "原件.md").copyTo(File(vault, "副本.md"))
        File(vault, "无效.md").writeText("---\nlife_archive_id: invalid\n---\n")
        sync.synchronize(); assertCompleted()
        assertEquals(3, sync.state.value.issues.size)
        assertNotNull(notes.repository.read(created.summary.id).get())
        assertEquals(1, notes.library.query().get().notes.size)
    }
    @Test fun draftsAndStaleWritesAreRejected() = runBlocking {
        val created = notes.repository.create("草稿.md", RawDocument("saved")).get()
        sync.synchronize(); assertCompleted()
        val inventory = notes.sync.inventory().get()
        val expected = inventory.files.single { it.kind == "note" }
        val current = notes.repository.read(created.summary.id).get()
        notes.repository.writeDraft(Draft(current.summary.id, current.summary.version, 1, RawDocument("unsaved"))).get()
        File(vault, "草稿.md").appendText("pc")
        sync.synchronize()
        assertTrue(sync.state.value.message, sync.state.value.message.contains("草稿"))
        assertTrue(notes.sync.apply(expected, null) is NoteResult.StorageFailure)
        assertEquals("unsaved", notes.repository.readDraft(created.summary.id).get()!!.document.text)
    }
    @Test fun samePathWithDifferentIdsRequiresExplicitChoice() = runBlocking {
        val created = notes.repository.create("同名.md", RawDocument("phone")).get()
        File(vault, "同名.md").writeText(NoteIdentity.ensure("computer"))
        sync.synchronize(); assertCompleted()
        val conflict = sync.state.value.conflicts.single()
        assertTrue(conflict.key.startsWith("path:"))
        sync.synchronize(mapOf(conflict.key to SyncChoice.PHONE)); assertCompleted()
        assertTrue(File(vault, "同名.md").readText().endsWith("phone"))
        assertEquals(1, notes.library.query().get().notes.size)
        assertEquals(created.summary.id.value, NoteIdentity.read(File(vault, "同名.md").readText()))
    }
    @Test fun completeRestoreClearsCredentialAndDeletionBaseline() = runBlocking {
        notes.repository.create("备份.md", RawDocument("saved")).get()
        sync.synchronize(); assertCompleted()
        val output = ByteArrayOutputStream(); backup.export(output)
        File(vault, "备份.md").delete()
        val prepared = backup.prepare(output.toByteArray().inputStream())
        assertTrue(backup.commit(prepared) is RestoreResult.Committed)
        sync.refreshTrash(); assertFalse(sync.state.value.paired)
        val connection = URL("$address/admin/pairing").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        val pairing = try { Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject.getValue("code").jsonPrimitive.content } finally { connection.disconnect() }
        sync.connect(address, pairing)
        sync.synchronize(); assertCompleted()
        assertTrue(File(vault, "备份.md").exists())
        assertEquals(1, notes.library.query().get().notes.size)
    }
    @Test fun connectionFailureLeavesLocalFilesAndCanRetryWithoutDuplicates() = runBlocking {
        val created = notes.repository.create("断线.md", RawDocument("saved")).get()
        process.destroy(); process.waitFor(3, TimeUnit.SECONDS)
        sync.synchronize()
        assertTrue(sync.state.value.message.startsWith("操作未完成"))
        assertEquals("saved", notes.repository.read(created.summary.id).get().document.text)
        assertEquals(1, notes.library.query().get().notes.size)
    }
    @Test fun scale500Notes200MiBAttachmentsAndNoChangeTransfer() = runBlocking {
        repeat(500) { index -> notes.repository.create("合成笔记/笔记-$index.md", RawDocument("# 合成 $index\n" + "中英 mixed text\n".repeat(500))).get() }
        var remaining = 200L * 1024 * 1024
        repeat(100) { index ->
            val size = if (index == 0) 50L * 1024 * 1024 else if (index == 99) remaining else 150L * 1024 * 1024 / 99
            remaining -= size
            val input = object : java.io.InputStream() {
                var rest = size
                override fun read(): Int { if (rest == 0L) return -1; rest--; return 0 }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (rest == 0L) return -1
                    val count = minOf(rest, length.toLong()).toInt(); bytes.fill(0, offset, offset + count); rest -= count; return count
                }
            }
            notes.repository.importAttachment("合成附件/附件-$index.bin", input).get()
        }
        val start = System.nanoTime()
        sync.synchronize(); assertCompleted()
        assertTrue(sync.state.value.issues.toString(), sync.state.value.issues.isEmpty())
        val firstMs = (System.nanoTime() - start) / 1_000_000
        val transferred = sync.state.value.transferredBytes
        assertTrue(transferred >= 200L * 1024 * 1024)
        assertEquals(50L * 1024 * 1024, File(vault, "合成附件/附件-0.bin").length())
        val second = System.nanoTime()
        sync.synchronize(); assertCompleted(); assertEquals(0L, sync.state.value.transferredBytes)
        val unchangedMs = (System.nanoTime() - second) / 1_000_000
        val evidence = File(System.getenv("LIFE_ARCHIVE_PHASE3_EVIDENCE") ?: File(System.getProperty("java.io.tmpdir"), "life-archive-phase3").absolutePath)
        evidence.mkdirs()
        File(evidence, "sync-scale.json").writeText("""{"notes":500,"attachments":100,"attachmentBytes":209715200,"largestAttachmentBytes":52428800,"firstMs":$firstMs,"unchangedMs":$unchangedMs,"transferredBytes":$transferred,"unchangedTransferredBytes":0,"device":"desktop-robolectric-http"}""")
    }
    @Test fun cancellationDuringRealHttpWaitPreservesDataAndReleasesSyncMutex() = runBlocking {
        val created = notes.repository.create("取消.md", RawDocument("saved")).get()
        val marker = File(vault.parentFile, "delay-manifest"); marker.writeText("300")
        val job = launch(Dispatchers.Default) { sync.synchronize() }
        withTimeout(10000) { while (marker.exists()) delay(10) }
        job.cancelAndJoin()
        assertFalse(sync.state.value.busy)
        assertEquals("saved", notes.repository.read(created.summary.id).get().document.text)
        sync.synchronize(); assertCompleted()
        assertEquals(1, notes.library.query().get().notes.size)
    }
}
