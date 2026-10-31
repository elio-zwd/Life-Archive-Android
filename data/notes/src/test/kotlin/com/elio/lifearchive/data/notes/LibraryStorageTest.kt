package com.elio.lifearchive.data.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.domain.notes.*
import java.io.*
import java.util.zip.ZipInputStream
import java.security.MessageDigest
import java.util.UUID
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class LibraryStorageTest {
    @Test fun excerptsUseIndexedBodyWithoutChangingTheOriginalDocument() = runBlocking {
        val root = temp.newFolder()
        open(root).use { storage ->
            val raw = "\uFEFF---\r\ntitle: YAML标题\r\ntags: [项目]\r\nunknown: 保留\r\n---\r\n# 正文标题\r\n\r\n正文 **重要** [链接](目标.md) `代码`。\r\n"
            val note = storage.create("本子/文件名.md", raw)
            val detail = (storage.library.query() as NoteResult.Success).value.notes.single()
            assertEquals("正文 重要 链接 代码。", detail.excerpt)
            assertEquals("YAML标题", detail.title)
            assertEquals(raw, storage.text(note.summary.id))
            assertTrue(File(root, "documents/本子/文件名.md").renameTo(File(root, "暂存正文.md")))
            val metadata = (storage.library.metadata(note.summary.id) as NoteResult.Success).value
            assertEquals(detail, metadata)
            assertEquals(NoteResult.NotFound, storage.library.metadata(NoteId(UUID.randomUUID().toString())))
        }
    }

    @Test fun searchExcerptShowsBodyHitAndKeepsOriginalFirstWordOffset() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val raw = "---\ntitle: 标题\n---\n# 首标题\n" + "甲".repeat(220) + " **关键命中** " + "乙".repeat(220)
            val note = storage.create("文件名.md", raw)
            val direct = (storage.library.query(NotesQuery(text = "关键命中")) as NoteResult.Success).value.notes.single()
            assertEquals(raw.indexOf("关键命中"), direct.matchOffset)
            assertTrue(direct.excerpt.contains("关键命中"))
            assertTrue(direct.excerpt.length <= 160)
            assertFalse(direct.excerpt.contains("首标题"))
            val split = (storage.library.query(NotesQuery(text = "文件名 关键命中")) as NoteResult.Success).value.notes.single()
            assertEquals(note.summary.id, split.summary.id)
            assertEquals(0, split.matchOffset)
            assertTrue(split.excerpt.contains("关键命中"))
            assertTrue(split.excerpt.length <= 160)
        }
    }

    @Test fun excerptCleansTaskListAndHighlightMarkersWithoutChangingCodeOrOriginal() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val raw = "# 标题\n- [ ] **相机**\n- [x] ==充电==\n1. 线缆\n- 普通\n`==行内代码==`\n```text\n- [ ] 代码里的方框\n==代码里的等号==\n```"
            val note = storage.create("清单.md", raw)
            val excerpt = (storage.library.query() as NoteResult.Success).value.notes.single().excerpt
            assertEquals("相机 充电 线缆 普通 ==行内代码== - [ ] 代码里的方框 ==代码里的等号==", excerpt)
            assertEquals(raw, storage.text(note.summary.id))
        }
    }

    @Test fun searchExcerptKeepsCodeMarkersWhenTheHitIsDeepInsideAFence() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val raw = "# 标题\n```text\n" + "上文".repeat(200) + "\n- [ ] ==关键命中==\n```\n"
            val note = storage.create("代码.md", raw)
            val excerpt = (storage.library.query(NotesQuery(text = "关键命中")) as NoteResult.Success).value.notes.single().excerpt
            assertTrue(excerpt.contains("- [ ] ==关键命中=="))
            assertEquals(raw, storage.text(note.summary.id))
        }
    }

    @Test fun excerptPreservesIndentedAndMultilineCodeButCleansNestedTasks() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val raw = "# 标题\n\n    ==缩进代码== **原样**\n    - [ ] 字面\n\n- 父清单\n    - [ ] ==子任务==\n\n`跨\n==行代码==` 和 `` ==反引号 ` 原样== ``"
            val note = storage.create("代码与清单.md", raw)
            val excerpt = (storage.library.query() as NoteResult.Success).value.notes.single().excerpt
            assertEquals("==缩进代码== **原样** - [ ] 字面 父清单 子任务 跨 ==行代码== 和 ==反引号 ` 原样==", excerpt)
            assertEquals(raw, storage.text(note.summary.id))
        }
    }

    @Test fun searchExcerptProtectsInlineCodeWhoseClosingMarkerIsOutsideTheWindow() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val raw = "前文 `开始\n" + "甲".repeat(180) + "==关键命中==\n" + "乙".repeat(200) + "` 后文"
            val note = storage.create("跨行代码.md", raw)
            val excerpt = (storage.library.query(NotesQuery(text = "关键命中")) as NoteResult.Success).value.notes.single().excerpt
            assertTrue(excerpt.contains("==关键命中=="))
            assertEquals(raw, storage.text(note.summary.id))
        }
    }

    @Test fun thumbnailKeepsLocalReferenceSyntaxAndResolvesRelativeAttachment() = runBlocking {
        open(temp.newFolder()).use { storage ->
            storage.repository.importAttachment("图 一.PNG", byteArrayOf(1).inputStream())
            storage.repository.importAttachment("本子/图 一.PNG", byteArrayOf(2).inputStream())
            val raw = "---\ncover: ![[yaml.png]]\n---\n# 标题\n![远程](https://example.com/remote.png)\n[普通链接](普通.png)\n![[声音.mp3]]\n![本地](../图%20一.PNG)"
            val note = storage.create("本子/子/引用.md", raw)
            val candidate = (storage.library.query() as NoteResult.Success).value.notes.single().thumbnail!!
            assertEquals("../图%20一.PNG", candidate.target)
            assertEquals(LinkSyntax.MARKDOWN, candidate.syntax)
            val resolved = (storage.library.resolve(note.summary.id, candidate.target, candidate.syntax) as NoteResult.Success).value
            assertEquals("本子/图 一.PNG", resolved.attachment!!.value)
            val opened = storage.library.openResource(resolved.attachment!!) as NoteResult.Success
            assertArrayEquals(byteArrayOf(2), opened.value.stream.use { it.readBytes() })
            assertEquals(raw, storage.text(note.summary.id))
        }
    }

    @Test fun importingAttachmentsPublishesChangedReferenceResolutionEvenWhenNotesStayTheSame() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val note = storage.create("本子/引用.md", "![[图片.png]]")
            val snapshots = Channel<List<NoteSummary>>(Channel.UNLIMITED)
            val observer = launch { storage.repository.observeNotes().collect { snapshots.send(it) } }
            try {
                val initial = withTimeout(5_000) { snapshots.receive() }
                assertTrue((storage.library.resolve(note.summary.id, "图片.png") as NoteResult.Success).value.missing)

                assertTrue(storage.repository.importAttachment("图片.png", byteArrayOf(1).inputStream()) is NoteResult.Success)
                assertEquals(initial, withTimeout(5_000) { snapshots.receive() })
                assertEquals("图片.png", (storage.library.resolve(note.summary.id, "图片.png") as NoteResult.Success).value.attachment!!.value)

                assertTrue(storage.repository.importAttachment("其他/图片.png", byteArrayOf(2).inputStream()) is NoteResult.Success)
                assertEquals(initial, withTimeout(5_000) { snapshots.receive() })
                val ambiguous = (storage.library.resolve(note.summary.id, "图片.png") as NoteResult.Success).value
                assertNull(ambiguous.attachment)
                assertEquals(setOf("图片.png", "其他/图片.png"), ambiguous.ambiguous.toSet())
            } finally {
                observer.cancelAndJoin()
                snapshots.close()
            }
        }
    }

    @Test fun wikiAndHtmlImageCandidatesExcludeRemoteAndYamlReferences() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val wiki = storage.create("wiki.md", "---\ncover: ![[yaml.webp]]\n---\n![[https://example.com/remote.png]]\n![[图片.webp|200]]")
            val html = storage.create("html.md", "<img src=\"//example.com/remote.png\">\n<img src=\"文件夹/图片.jpg\">")
            val listing = (storage.library.query() as NoteResult.Success).value.notes.associateBy { it.summary.id }
            assertEquals(ThumbnailReference("图片.webp", LinkSyntax.WIKI), listing.getValue(wiki.summary.id).thumbnail)
            assertEquals(ThumbnailReference("文件夹/图片.jpg", LinkSyntax.HTML), listing.getValue(html.summary.id).thumbnail)
        }
    }

    @Test fun staleIndexNeverProvidesOldExcerptOrThumbnailAndRebuildRefreshesThem() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val note = storage.create("笔记.md", "# 旧标题\n旧正文 ![[旧.png]]")
            assertEquals("旧正文", (storage.library.metadata(note.summary.id) as NoteResult.Success).value.excerpt)
            storage.fault = { if (it == "index") throw IOException("索引故障") }
            val changed = storage.repository.save(note.summary.id, note.summary.version, RawDocument("# 新标题\n新正文 ![[新.jpg]]")) as NoteResult.Committed
            assertTrue(changed.indexPending)
            val pending = (storage.library.metadata(note.summary.id) as NoteResult.Success).value
            assertTrue(pending.summary.indexPending)
            assertEquals("", pending.excerpt)
            assertNull(pending.thumbnail)
            storage.fault = {}
            assertTrue(storage.repository.rebuildIndex() is NoteResult.Success)
            val refreshed = (storage.library.metadata(note.summary.id) as NoteResult.Success).value
            assertEquals("新正文", refreshed.excerpt)
            assertEquals(ThumbnailReference("新.jpg", LinkSyntax.WIKI), refreshed.thumbnail)
        }
    }

    @Test fun listRefreshAndSplitFieldSearchPreserveTagsOffsetsAndTrashFiltering() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val raw = "---\ntitle: 独立标题\naliases: [别名]\ntags: [项目/开发]\n---\n正文 MixedCase 关键词"
            val note = storage.create("本子/文件名.md", raw)
            val listing = (storage.library.query() as NoteResult.Success).value
            assertEquals(note.summary.id, listing.notes.single().summary.id)
            assertEquals(listOf("项目/开发"), listing.notes.single().tags)
            for (text in listOf("关键词 文件名", "MixedCase 独立标题", "别名 关键词", " 关键词 \n\t文件名 ")) {
                val result = (storage.library.query(NotesQuery(text = text, folder = "本子", tag = "项目")) as NoteResult.Success).value.notes.single()
                assertEquals(note.summary.id, result.summary.id)
                val firstWord = text.trim().split(Regex("\\s+")).first()
                assertEquals(raw.indexOf(firstWord, ignoreCase = true).coerceAtLeast(0), result.matchOffset)
            }
            assertTrue((storage.library.query(NotesQuery(text = "不存在 关键词")) as NoteResult.Success).value.notes.isEmpty())
            assertTrue(storage.library.trash(note.summary.id, note.summary.version) is NoteResult.Success)
            assertTrue((storage.library.query() as NoteResult.Success).value.notes.isEmpty())
            assertEquals(note.summary.id, (storage.library.query(NotesQuery(text = "关键词 文件名", trash = true)) as NoteResult.Success).value.notes.single().summary.id)
        }
    }
    @Test fun toolbarLinksResolveSpecialPathsAndDoNotSelectTheRootNamesake() = runBlocking {
        open(temp.newFolder()).use { storage ->
            storage.create("目标(1).md", "根目录")
            val local = storage.create("本子/目标(1).md", "本子目标")
            val special = storage.create("资料/a#b(c).md", "特殊文件")
            val from = storage.create("本子/引用.md", "正文")
            for (target in listOf(local, special)) {
                val markup = MarkdownReferences.relative(from.summary.path, target.summary.path, "显示[名]")
                val link = DocumentAnalyzer.analyze(markup).links.single()
                assertEquals(target.summary.id, (storage.library.resolve(from.summary.id, link.target, link.syntax) as NoteResult.Success).value.note)
            }
            val wiki = MarkdownReferences.wiki(local.summary.path, "同名显示")!!
            val link = DocumentAnalyzer.analyze(wiki).links.single()
            assertEquals(local.summary.id, (storage.library.resolve(from.summary.id, link.target, link.syntax) as NoteResult.Success).value.note)
            assertNull(MarkdownReferences.wiki(special.summary.path))
        }
    }
    @Test fun relocationPreservesAbsolutePathsFragmentsAndUnchangedSourceRepresentation() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val target = storage.create("目标(1).md", "# 小节")
            val raw = "[显示](</目标(1).md#小节> \"标题保留\")\n<a href=\"/目标(1).md#小节&amp;保留\">显示</a>\n[[目标(1)]]"
            val referring = storage.create("本子/引用.md", raw)
            assertTrue(storage.library.relocate(referring.summary.id, referring.summary.version, "另一本子/引用.md") is NoteResult.Success)
            assertEquals(raw, storage.text(referring.summary.id))
            assertTrue(storage.library.relocate(target.summary.id, target.summary.version, "新目标.md") is NoteResult.Success)
            val changed = storage.text(referring.summary.id)
            assertTrue(changed.contains("</%E6%96%B0%E7%9B%AE%E6%A0%87.md#小节> \"标题保留\""))
            assertTrue(changed.contains("#小节&amp;保留\""))
        }
    }
    @Test fun relativeLinksDoNotFollowTheVaultRootNamesakeAndExportsKeepTheActualTarget() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val root = storage.create("目标(1).md", "根目录目标")
            val local = storage.create("本子/目标(1).md", "相对目标")
            val from = storage.create("本子/引用.md", "[显示](目标(1).md \"原始标题\")")
            val result = storage.library.resolve(from.summary.id, "目标(1).md", com.elio.lifearchive.core.document.LinkSyntax.MARKDOWN) as NoteResult.Success
            assertEquals(local.summary.id, result.value.note)
            assertTrue((storage.library.resolve(from.summary.id, "目标(1)") as NoteResult.Success).value.ambiguous.size == 2)
            assertTrue(storage.library.relocate(root.summary.id, root.summary.version, "根目录新名.md") is NoteResult.Success)
            assertEquals("[显示](目标(1).md \"原始标题\")", storage.text(from.summary.id))
            assertTrue(storage.library.relocate(local.summary.id, local.summary.version, "本子/新名.md") is NoteResult.Success)
            assertTrue(storage.text(from.summary.id).contains("%E6%96%B0%E5%90%8D.md \"原始标题\""))
            val bytes = java.io.ByteArrayOutputStream(); storage.library.export(bytes, setOf(from.summary.id))
            val names = mutableListOf<String>()
            java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> while (true) { val item = zip.nextEntry ?: break; names += item.name; zip.closeEntry() } }
            assertTrue("本子/新名.md" in names); assertFalse("根目录新名.md" in names)
        }
    }

    @Test fun rollbackCleanupInterruptionDoesNotRequireTheDeletedSnapshotAgain() = runBlocking {
        val directory = temp.newFolder()
        val storage = open(directory)
        val original = storage.create("原名.md", "不可丢失")
        storage.fault = {
            if (it == "batch-note") throw java.io.IOException("触发回滚")
            if (it == "batch-cleanup") throw AssertionError("清理后进程退出")
        }
        try { storage.library.relocate(original.summary.id, original.summary.version, "新名.md"); fail("应中断") } catch (_: AssertionError) { }
        storage.close()
        open(directory).use { reopened ->
            assertTrue(reopened.repository.initialize() is NoteResult.Success)
            assertEquals("不可丢失", reopened.text(original.summary.id))
            assertEquals("原名.md", (reopened.repository.read(original.summary.id) as NoteResult.Success).value.summary.path.value)
        }
    }
    @Test fun rebuildingAFailedProjectionInvalidatesEmptyMetadata() = runBlocking {
        open(temp.newFolder()).use { storage ->
            storage.fault = { if (it == "index") throw java.io.IOException("索引失败") }
            storage.create("失败.md", "---\ntags: [必须恢复]\n---\n正文关键词")
            assertTrue((storage.library.query(NotesQuery(text = "关键词")) as NoteResult.Success).value.notes.isEmpty())
            storage.fault = {}
            assertTrue(storage.repository.rebuildIndex() is NoteResult.Success)
            assertEquals(1, (storage.library.query(NotesQuery(text = "关键词", tag = "必须恢复")) as NoteResult.Success).value.notes.size)
        }
    }
    @get:Rule val temp = TemporaryFolder()
    private fun open(root: File) = NotesStorage.open(ApplicationProvider.getApplicationContext<Context>(), root, StorageGate())
    private fun committed(result: NoteResult<NoteSnapshot>) = (result as NoteResult.Committed).value
    private suspend fun NotesStorage.create(path: String, text: String) = committed(repository.create(path, RawDocument(text)))
    private suspend fun NotesStorage.text(id: NoteId) = (repository.read(id) as NoteResult.Success).value.document.text
    private class Crash : Error()
    @Test fun failedImportRollsBackAndClearsProgressInsteadOfLeavingAWorkingIndicator() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val prepared = (storage.library.prepareImport(listOf(ImportEntry("一.md") { "原文".byteInputStream() }), "导入") as NoteResult.Success).value
            storage.fault = { if (it == "batch-import") throw IOException("写入故障") }
            assertTrue(storage.library.commitImport(prepared) is NoteResult.StorageFailure)
            assertNull(storage.library.progress.first())
            assertTrue((storage.library.query() as NoteResult.Success).value.notes.isEmpty())
        }
    }
    @Test fun mermaidInternalLinksAndRestoredPathsMaintainRecognizableTargets() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val note = storage.create("目标.md", "# 目标")
            val diagram = storage.create("图表.md", "```mermaid\ngraph TD\n A[\"目标\"] --> B\n class A internal-link;\n```\n[[目标]]")
            assertTrue(storage.library.relocate(note.summary.id, note.summary.version, "新目标.md") is NoteResult.Success)
            assertTrue(storage.text(diagram.summary.id).contains("A[\"新目标\"]"))
            val latest = (storage.repository.read(note.summary.id) as NoteResult.Success).value
            storage.library.trash(note.summary.id, latest.summary.version)
            assertTrue(storage.library.restore(note.summary.id, "恢复/新目标.md") is NoteResult.Success)
            assertTrue(storage.text(diagram.summary.id).contains("[[恢复/新目标]]"))
        }
    }

    @Test fun versionOneRoomDatabaseMigratesWithoutLosingIdentityOrBody() = runBlocking {
        val root = temp.newFolder(); val id = UUID.randomUUID().toString(); val version = UUID.randomUUID().toString(); val raw = "\uFEFF# 旧版本\r\n正文"
        File(root, "documents").mkdirs(); File(root, "documents/旧笔记.md").writeText(raw)
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        SQLiteDatabase.openOrCreateDatabase(File(root, "state.db"), null).use { database ->
            database.execSQL("CREATE TABLE notes (id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, pathKey TEXT NOT NULL, version TEXT NOT NULL, hash TEXT NOT NULL, indexPending INTEGER NOT NULL, pinned INTEGER NOT NULL, readingOffset INTEGER NOT NULL)")
            database.execSQL("CREATE UNIQUE INDEX index_notes_pathKey ON notes(pathKey)")
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table VALUES(42, '3e531801cb19b46d546d2120568ff4a4')")
            database.execSQL("INSERT INTO notes VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>(id, "旧笔记.md", "旧笔记.md", version, hash, 1, 1, 7))
            database.version = 1
        }
        open(root).use { storage ->
            assertTrue(storage.repository.initialize() is NoteResult.Success)
            assertEquals(raw, storage.text(NoteId(id)))
            assertEquals(7, (storage.repository.readReadingOffset(NoteId(id)) as NoteResult.Success).value)
            assertTrue((storage.library.query() as NoteResult.Success).value.notes.single().pinned)
        }
    }
    @Test fun legacyBackupAndNewTrashFolderStateCanBothBeRestored() = runBlocking {
        val root = temp.newFolder()
        open(root).use { storage ->
            val old = storage.create("原笔记.md", "旧原文")
            val legacy = temp.newFolder(); storage.backupParticipant.snapshot(legacy)
            val stateFile = File(legacy, "state.json"); val json = JSONObject(stateFile.readText()); assertEquals(3, json.getInt("format")); json.remove("format"); json.remove("folders")
            val record = json.getJSONArray("notes").getJSONObject(0); record.remove("created"); record.remove("modified"); record.remove("deleted"); stateFile.writeText(json.toString())
            storage.repository.save(old.summary.id, old.summary.version, RawDocument("修改后"))
            storage.backupParticipant.restore(legacy)
            assertEquals("旧原文", storage.text(old.summary.id))
            storage.library.createFolder("空目录")
            val current = (storage.repository.read(old.summary.id) as NoteResult.Success).value
            storage.library.trash(old.summary.id, current.summary.version)
            val saved = temp.newFolder(); storage.backupParticipant.snapshot(saved)
            storage.library.purge(old.summary.id)
            storage.backupParticipant.restore(saved)
            assertEquals(old.summary.id, (storage.library.query(NotesQuery(trash = true)) as NoteResult.Success).value.notes.single().summary.id)
            assertTrue("空目录" in (storage.library.query() as NoteResult.Success).value.folders)
        }
    }

    @Test fun renameUpdatesResolvedTargetsAndLeavesCodeTitlesYamlAndDisplayAliasAlone() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val target = storage.create("甲/旧名.md", "---\ntitle: 独立标题\n---\n# 正文标题\n\n## 小节\n内容 ^block\n")
            val source = "\uFEFF---\r\nunknown: {keep: true}\r\n---\r\n[[甲/旧名#小节|显示别名]] [链接](%E7%94%B2/%E6%97%A7%E5%90%8D.md#^block)\r\n`[[甲/旧名]]`\r\n```md\r\n[[甲/旧名]]\r\n```\r\n"
            val referring = storage.create("引用.md", source)
            assertTrue(storage.library.relocate(target.summary.id, target.summary.version, "乙/新名.md") is NoteResult.Success)
            val text = storage.text(referring.summary.id)
            assertTrue(text.contains("[[乙/新名#小节|显示别名]]")); assertTrue(text.contains("%E4%B9%99/%E6%96%B0%E5%90%8D.md#^block"))
            assertTrue(text.contains("`[[甲/旧名]]`")); assertTrue(text.contains("```md\r\n[[甲/旧名]]"))
            assertTrue(text.startsWith("\uFEFF---\r\nunknown: {keep: true}")); assertTrue(storage.text(target.summary.id).contains("title: 独立标题"))
            val result = storage.library.resolve(referring.summary.id, "乙/新名#小节") as NoteResult.Success
            assertEquals(target.summary.id, result.value.note); assertNotNull(result.value.location.offset)
        }
    }
    @Test fun movedSourceKeepsRelativeAttachmentsAndNotebookMovesKeepEmptyFolders() = runBlocking {
        open(temp.newFolder()).use { storage ->
            storage.library.createFolder("本子/空目录")
            storage.repository.importAttachment("本子/图片.png", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            val note = storage.create("本子/记录.md", "![图](图片.png)")
            assertTrue(storage.library.relocateFolder("本子", "移动后") is NoteResult.Success)
            val listing = (storage.library.query() as NoteResult.Success).value
            assertTrue("移动后/空目录" in listing.folders)
            assertEquals("移动后/记录.md", listing.notes.single().summary.path.value)
            assertEquals("![图](图片.png)", storage.text(note.summary.id))
            val resolved = (storage.library.resolve(note.summary.id, "图片.png") as NoteResult.Success).value
            assertEquals("移动后/图片.png", resolved.attachment!!.value)
        }
    }
    @Test fun conflictsAndAffectedDraftsPreventMutation() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val target = storage.create("旧.md", "原文")
            val referring = storage.create("其他.md", "无引用")
            storage.repository.writeDraft(Draft(referring.summary.id, referring.summary.version, 1, RawDocument("[[旧]] 新草稿")))
            assertTrue(storage.library.relocate(target.summary.id, target.summary.version, "新.md") is NoteResult.StorageFailure)
            assertEquals("原文", storage.text(target.summary.id))
            assertTrue(storage.library.relocate(target.summary.id, DocumentVersion("过期"), "新.md") is NoteResult.Conflict)
            assertEquals("其他.md", (storage.library.query(NotesQuery(text = "其他")) as NoteResult.Success).value.notes.single().summary.path.value)
        }
    }
    @Test fun interruptedBatchRollsBackBeforeCommitAndKeepsCommittedResultAfterMarker() = runBlocking {
        for (point in listOf("batch-journal", "batch-note", "batch-files", "batch-committed")) {
            val root = temp.newFolder()
            val note = open(root).use { storage ->
                val note = storage.create("旧.md", "内容")
                storage.create("引用.md", "[[旧]]")
                storage.fault = { if (it == point) throw Crash() }
                try { storage.library.relocate(note.summary.id, note.summary.version, "新.md"); fail("应中断") } catch (_: Crash) { }
                note
            }
            open(root).use { storage ->
                assertTrue(storage.repository.initialize() is NoteResult.Success)
                val listing = (storage.library.query() as NoteResult.Success).value
                assertEquals(if (point == "batch-committed") "新.md" else "旧.md", listing.notes.first { it.summary.id == note.summary.id }.summary.path.value)
                val reference = listing.notes.first { it.summary.path.value == "引用.md" }
                assertEquals(if (point == "batch-committed") "[[新]]" else "[[旧]]", storage.text(reference.summary.id))
                assertFalse(File(root, "operations/batch.json").exists())
            }
        }
    }
    @Test fun trashRestoreConflictAndPurgePreserveSharedAttachments() = runBlocking {
        open(temp.newFolder()).use { storage ->
            storage.repository.importAttachment("共享.png", ByteArrayInputStream(byteArrayOf(7, 8, 9)))
            val note = storage.create("记录.md", "![[共享.png]]")
            assertTrue(storage.library.trash(note.summary.id, note.summary.version) is NoteResult.Success)
            assertTrue((storage.library.query() as NoteResult.Success).value.notes.isEmpty())
            storage.create("记录.md", "新的笔记")
            assertEquals(NoteResult.PathConflict, storage.library.restore(note.summary.id))
            assertTrue(storage.library.restore(note.summary.id, "恢复/记录.md") is NoteResult.Success)
            val restored = (storage.repository.read(note.summary.id) as NoteResult.Success).value
            storage.library.trash(note.summary.id, restored.summary.version)
            assertTrue(storage.library.purge(note.summary.id) is NoteResult.Success)
            val stream = storage.library.openResource(LibraryPath("共享.png"), 1, 1) as NoteResult.Success
            assertArrayEquals(byteArrayOf(8), stream.value.stream.use { it.readBytes() })
        }
    }
    @Test fun searchTagsPinningAndSortUseIndexWithoutReadingBodyFiles() = runBlocking {
        val root = temp.newFolder()
        open(root).use { storage ->
            val first = storage.create("本子/甲.md", "---\ntags: [项目/开发]\n---\n# 正文标题\n中文连续关键词 MixedCase")
            val other = storage.create("乙.md", "另外的笔记")
            storage.library.setPinned(first.summary.id, true)
            val tagged = (storage.library.query(NotesQuery(text = "关键词 mixedcase", folder = "本子", tag = "项目")) as NoteResult.Success).value
            assertEquals(first.summary.id, tagged.notes.single().summary.id)
            assertEquals(first.summary.id, (storage.library.query() as NoteResult.Success).value.notes.first().summary.id)
            File(root, "documents/本子/甲.md").renameTo(File(root, "暂时不读.md"))
            assertEquals(first.summary.id, (storage.library.query(NotesQuery(text = "正文标题")) as NoteResult.Success).value.notes.single().summary.id)
            assertNotEquals(first.summary.id, other.summary.id)
        }
    }
    @Test fun importCancelCommitAndPortableExportMaintainPathsWithoutOverwriting() = runBlocking {
        open(temp.newFolder()).use { storage ->
            val entries = listOf(ImportEntry("甲.md") { "[[子目录/乙]] ![[图.png]]".byteInputStream() }, ImportEntry("子目录/乙.md") { "# 乙".byteInputStream() }, ImportEntry("图.png") { byteArrayOf(1, 2).inputStream() })
            val canceled = (storage.library.prepareImport(entries, "取消") as NoteResult.Success).value
            storage.library.discardImport(canceled)
            assertTrue((storage.library.query() as NoteResult.Success).value.notes.isEmpty())
            val prepared = (storage.library.prepareImport(entries, "导入") as NoteResult.Success).value
            assertTrue(storage.library.commitImport(prepared) is NoteResult.Success)
            val listing = (storage.library.query() as NoteResult.Success).value
            val first = listing.notes.first { it.summary.path.value == "导入/甲.md" }
            assertTrue(storage.text(first.summary.id).contains("[[导入/子目录/乙]]"))
            assertEquals("导入/图.png", (storage.library.resolve(first.summary.id, "导入/图.png") as NoteResult.Success).value.attachment!!.value)
            val output = ByteArrayOutputStream(); assertTrue(storage.library.export(output, setOf(first.summary.id)) is NoteResult.Success)
            val paths = mutableListOf<String>(); ZipInputStream(output.toByteArray().inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; paths += entry.name; zip.closeEntry() } }
            assertTrue(paths.containsAll(listOf("导入/甲.md", "导入/子目录/乙.md", "导入/图.png")))
            val duplicate = (storage.library.prepareImport(entries, "导入") as NoteResult.Success).value
            assertTrue(storage.library.commitImport(duplicate) is NoteResult.StorageFailure)
        }
    }
    @Test fun malformedUtf8AndTraversalAreRejectedBeforeLibraryChanges() = runBlocking {
        open(temp.newFolder()).use { storage ->
            assertTrue(storage.library.prepareImport(listOf(ImportEntry("坏.md") { byteArrayOf(0xc3.toByte(), 0x28).inputStream() }), "导入") is NoteResult.StorageFailure)
            assertTrue(storage.library.prepareImport(listOf(ImportEntry("../越界.md") { "数据".byteInputStream() }), "导入") is NoteResult.StorageFailure)
            assertTrue((storage.library.query() as NoteResult.Success).value.notes.isEmpty())
        }
    }
}
