package com.elio.lifearchive.data.pet

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.domain.ai.AiGateway
import com.elio.lifearchive.domain.pet.DEFAULT_COMPANION_MODEL_ID
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalLive2DModelStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun createTempDir(prefix: String): File =
        File.createTempFile(prefix, "").apply { delete(); mkdirs() }

    private fun writeValidModel(dir: File, name: String) {
        dir.mkdirs()
        File(dir, "$name.moc3").writeBytes(byteArrayOf(0x4D, 0x4F, 0x43, 0x33, 1, 2, 3))
        File(dir, "$name.2048").mkdirs()
        File(dir, "$name.2048/texture_00.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        File(dir, "$name.model3.json").writeText(
            """
            {
              "Version": 3,
              "FileReferences": {
                "Moc": "$name.moc3",
                "Textures": ["$name.2048/texture_00.png"]
              }
            }
            """.trimIndent(),
        )
    }

    @Test fun importNestedFolderPersistsModelSwitchesSelectionAndDeletesCleanly() = runBlocking {
        val storageRoot = createTempDir("models-store")
        val sourceWrapper = createTempDir("models-source")
        val dbName = "pet-import-${java.util.UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, CompanionDatabase::class.java, dbName)
            .addMigrations(CompanionDatabase.MIGRATION_1_2, CompanionDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // 模拟用户解压后多套了一层目录
            writeValidModel(File(sourceWrapper, "Tororo"), "Tororo")
            val store = LocalLive2DModelStore(storageRoot)
            val repo = AndroidCompanionRepository({ db }, AiGateway { error("不能联网") }, scope, modelStore = store)
            repo.initialize()
            assertTrue(repo.state.value.importedModels.isEmpty())

            val imported = repo.importModelFromTree(Uri.fromFile(sourceWrapper).toString())
            assertEquals("Tororo", imported.displayName)
            assertEquals("Tororo.model3.json", imported.modelJson)
            assertEquals(imported.id, repo.state.value.profile.modelId)
            assertEquals(1, repo.state.value.importedModels.size)
            assertTrue(File(imported.directoryPath, "Tororo.moc3").isFile)

            assertTrue(repo.deleteImportedModel(imported.id))
            assertTrue(repo.state.value.importedModels.isEmpty())
            assertEquals(DEFAULT_COMPANION_MODEL_ID, repo.state.value.profile.modelId)
            assertFalse(File(imported.directoryPath).exists())
        } finally {
            scope.cancel()
            db.close()
            storageRoot.deleteRecursively()
            sourceWrapper.deleteRecursively()
        }
    }

    @Test fun invalidOrTraversalModelPackageIsRejectedWithoutLeavingStagingFiles() {
        val storageRoot = createTempDir("models-store-bad")
        val missingMocDir = createTempDir("models-no-moc")
        val traversalDir = createTempDir("models-traversal")
        try {
            val store = LocalLive2DModelStore(storageRoot)

            File(missingMocDir, "Broken.model3.json").writeText(
                """{"Version":3,"FileReferences":{"Moc":"Broken.moc3","Textures":["tex.png"]}}""",
            )
            try {
                store.importFromTreeUri(Uri.fromFile(missingMocDir).toString())
                fail("缺少 .moc3 时应拒绝导入")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains(".moc3"))
            }

            File(traversalDir, "Bad.model3.json").writeText(
                """{"Version":3,"FileReferences":{"Moc":"../escape.moc3","Textures":["tex.png"]}}""",
            )
            try {
                store.importFromTreeUri(Uri.fromFile(traversalDir).toString())
                fail("路径穿越应被拒绝")
            } catch (_: IllegalArgumentException) {
            }

            assertTrue(store.list().isEmpty())
            assertTrue(storageRoot.listFiles().orEmpty().isEmpty())
        } finally {
            storageRoot.deleteRecursively()
            missingMocDir.deleteRecursively()
            traversalDir.deleteRecursively()
        }
    }
}
