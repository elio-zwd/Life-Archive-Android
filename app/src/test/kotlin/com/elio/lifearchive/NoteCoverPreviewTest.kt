package com.elio.lifearchive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.designsystem.LifeArchiveTheme
import com.elio.lifearchive.core.document.*
import com.elio.lifearchive.data.notes.NotesStorage
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.feature.notes.*
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 两篇示例先真实入库并关闭重开，再把查询与正式渲染投影交给预览。 */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [28], qualifiers = "w432dp-h960dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteCoverPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temp = TemporaryFolder()

    @Test fun realStoredExamplesRenderHomeAndCoverPickerAndExportReaderFixtures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(System.getenv("LIFE_ARCHIVE_COVER_PREVIEW") ?: "D:/Temp/life-archive-note-cover-preview-20261031").also { it.mkdirs() }
        val root = temp.newFolder()
        val ids = runBlocking {
            NotesStorage.open(context, root, StorageGate()).use { storage ->
                val first = (storage.repository.create("生活/山野慢行.md", RawDocument("---\ntags: [生活, 山野]\n---\n# 山野慢行\n\n沿着山间小路慢慢走，记录风的方向，也给忙碌的一周留一点空白。\n\n## 出发前\n- [ ] 带上相机和水\n- [ ] 查看天气与路线\n\n> 让脚步慢下来，风景就会靠近。")) as NoteResult.Committed).value
                assertTrue(storage.library.setCover(first.summary.id, NoteCover.Preset(CoverPreset.MOUNTAIN)) is NoteResult.Success)
                val imported = (importCoverImage(storage.repository, context.assets.open("covers/paper.png"), "窗边纸页.png") as NoteResult.Success).value
                val second = (storage.repository.create("灵感/窗边阅读.md", RawDocument("---\ntags: [阅读, 灵感]\n---\n# 窗边阅读\n\n午后的光落在书页上。留下一段读书笔记，记住今天让自己停下来的一句话。\n\n## 今天的摘录\n> 把平常的日子，过成值得保存的片段。\n\n## 阅读现场\n![窗边纸页](/${DocumentAnalyzer.encode(imported.path.value)})\n")) as NoteResult.Committed).value
                assertTrue(storage.library.setCover(second.summary.id, NoteCover.Attachment(imported.path)) is NoteResult.Success)
                listOf(first.summary.id, second.summary.id)
            }
        }
        NotesStorage.open(context, root, StorageGate()).use { storage ->
            val listing = runBlocking { assertTrue(storage.repository.initialize() is NoteResult.Success); (storage.library.query() as NoteResult.Success).value }
            assertEquals(2, listing.notes.size)
            val documents = JSONArray()
            runBlocking {
                ids.forEachIndexed { index, id ->
                    val snapshot = (storage.repository.read(id) as NoteResult.Success).value
                    val detail = (storage.library.metadata(id) as NoteResult.Success).value
                    val projection = HtmlProjection.project(snapshot.document.text)
                    val cover = when (val chosen = detail.cover) {
                        is NoteCover.Preset -> JSONObject().put("preset", chosen.preset.key)
                        is NoteCover.Attachment -> {
                            val resource = (storage.library.openResource(chosen.path) as NoteResult.Success).value
                            resource.stream.use { input -> File(output, "custom-cover.png").outputStream().use { input.copyTo(it) } }
                            JSONObject().put("attachment", chosen.path.value)
                        }
                    }
                    File(output, "example-${index + 1}.md").writeText(snapshot.document.text)
                    documents.put(JSONObject().put("id", id.value).put("name", snapshot.summary.path.value.substringAfterLast('/').removeSuffix(".md"))
                        .put("path", snapshot.summary.path.value).put("revision", snapshot.summary.version.value)
                        .put("html", projection.html).put("projectionKey", projection.digest)
                        .put("metadata", JSONObject().put("folder", snapshot.summary.path.value.substringBeforeLast('/')).put("modified", detail.modified)
                            .put("tags", JSONArray(detail.propertyTags)).put("cover", cover)))
                }
            }
            File(output, "documents.json").writeText(documents.toString(2))
            val model = NotesViewModel(storage.repository, storage.library)
            var dark by mutableStateOf(false)
            var picker by mutableStateOf<LibraryNote?>(null)
            var coverError by mutableStateOf<String?>(null)
            var dismissed = false
            compose.setContent {
                LifeArchiveTheme(dark) {
                    NotesScreen(NotesUiState(loading = false, notes = listing.notes.map { it.summary }, listing = listing), {}, { _, _ -> }, {}, {}, "浅色", {}, loadThumbnail = model::loadThumbnail)
                    picker?.let { note -> NoteCoverPicker(note, storage.library, notes = storage.repository, error = coverError, onPickImage = {},
                        onDismiss = { dismissed = true; picker = null },
                        onSave = { cover ->
                            val result = runBlocking { storage.library.setCover(note.summary.id, cover) }
                            if (result is NoteResult.Success) picker = null else coverError = result.failureMessage()
                        }) }
                }
            }
            fun save(name: String, view: android.view.View = compose.activity.findViewById(android.R.id.content)) {
                compose.waitUntil(15000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "封面加载中"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                compose.waitForIdle()
                compose.runOnIdle {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            // 主线程消息队列与 IO 均需完成，截图不得只包含图片占位。
            compose.waitUntil(15000) { model.state.value.listing?.notes?.size == 2 }
            runBlocking { model.loadThumbnail(ids[1], "/" + DocumentAnalyzer.encode((listing.notes.first { it.summary.id == ids[1] }.cover as NoteCover.Attachment).path.value), LinkSyntax.WIKI) }
            compose.waitForIdle()
            val imageBounds = compose.onNodeWithTag("note-thumbnail:${ids[0].value}", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val textBounds = compose.onNodeWithText("山野慢行", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(imageBounds.right < textBounds.left)
            save("home-light.png")
            compose.runOnIdle { dark = true }; save("home-dark.png")
            compose.runOnIdle { dark = false; picker = listing.notes.first { it.summary.id == ids[0] } }
            compose.onNodeWithTag("cover-picker").assertExists()
            save("picker-preset.png", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
            compose.onNodeWithText("自定义", useUnmergedTree = true).performClick()
            compose.onNodeWithText("从手机选择图片").assertExists()
            save("picker-custom-empty.png", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
            compose.onNodeWithText("预设", useUnmergedTree = true).performClick()
            compose.onNodeWithText("湖泊").performClick()
            compose.onNodeWithText("取消").performClick()
            assertTrue(dismissed)
            compose.runOnIdle { picker = listing.notes.first { it.summary.id == ids[1] } }
            compose.onNodeWithText("自定义", useUnmergedTree = true).assertExists()
            save("picker-custom.png", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
            compose.onNodeWithText("取消").performClick()
            runBlocking { assertEquals(NoteCover.Preset(), (storage.library.metadata(ids[0]) as NoteResult.Success).value.cover) }
            // 通过面板触发真实 Repository 保存，再重开核对。
            compose.runOnIdle { picker = listing.notes.first { it.summary.id == ids[0] } }
            compose.onNodeWithText("湖泊").performClick()
            compose.onNodeWithTag("cover-save").performClick()
            runBlocking { assertEquals(NoteCover.Preset(CoverPreset.LAKE), (storage.library.metadata(ids[0]) as NoteResult.Success).value.cover) }
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }
}
