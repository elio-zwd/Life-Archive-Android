package com.elio.jianyu.backup.complete

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteReplacementTest {
    private class SimulatedCrash : Error()
    private data class Fixture(val target: CompleteTarget, val staging: File, val manifest: CompleteArchiveCodec.Manifest, val life: File, val originalSettings: String, val originalEpoch: String)

    @Test fun everyInterruptedCommitStageRollsBackAndNeverTouchesLife() {
        for (stage in listOf("after-journal", "after-old-content", "after-database", "after-content", "after-settings", "after-epoch", "after-applied")) {
            val fixture = fixture()
            val operation = CompleteReplacement(fixture.target) { if (it == stage) throw SimulatedCrash() }
            try { operation.begin(fixture.staging, fixture.manifest); fail("未触发 $stage") } catch (_: SimulatedCrash) {}
            assertEquals("Life 不能变化", fixture.life.readText())
            val restarted = CompleteReplacement(fixture.target)
            assertTrue(restarted.recover())
            assertEquals("原见域数据库", fixture.target.database.readText())
            assertEquals("旧附件", File(fixture.target.content, "user-profile/keep.txt").readText())
            assertFalse(File(fixture.target.content, "user-profile/new.txt").exists())
            assertEquals(fixture.originalSettings, CompleteSettings.capture(fixture.target.preferences))
            assertEquals(fixture.originalEpoch, fixture.target.epoch.readText())
            assertEquals("Life 不能变化", fixture.life.readText())
            assertFalse(restarted.recover())
        }
    }

    @Test fun successfulReopenConfirmationKeepsNewContentAndChangesOnlyJianyuEpoch() {
        val fixture = fixture()
        val operation = CompleteReplacement(fixture.target)
        operation.begin(fixture.staging, fixture.manifest)
        assertEquals("新见域数据库", fixture.target.database.readText())
        assertTrue(operation.hasPending())
        assertNotEquals(fixture.originalEpoch, fixture.target.epoch.readText())
        operation.finalizeCommit()
        assertFalse(operation.hasPending())
        assertFalse(CompleteReplacement(fixture.target).recover())
        assertEquals("新附件", File(fixture.target.content, "user-profile/new.txt").readText())
        assertEquals("DARK", fixture.target.preferences("jianyu_app_preferences").getString("theme_mode", null))
        assertEquals("Life 不能变化", fixture.life.readText())
    }

    @Test fun damagedRollbackCopyStopsRecoveryAndKeepsJournalForRepair() {
        val fixture = fixture()
        val operation = CompleteReplacement(fixture.target) { if (it == "after-journal") throw SimulatedCrash() }
        try { operation.begin(fixture.staging, fixture.manifest); fail() } catch (_: SimulatedCrash) {}
        File(fixture.target.operations, "active/old.sqlite").writeText("损坏的回滚文件")
        try { operation.recover(); fail("损坏回滚不能报告完成") } catch (_: IllegalStateException) {}
        assertTrue(operation.hasPending())
        assertEquals("原见域数据库", fixture.target.database.readText())
        assertEquals("Life 不能变化", fixture.life.readText())
    }

    @Test fun interruptedPostCommitCleanupKeepsNewDataAndResumesBeforeOpeningRuntime() {
        val fixture = fixture()
        val operation = CompleteReplacement(fixture.target)
        operation.begin(fixture.staging, fixture.manifest)
        try { operation.finalizeCommit { throw SimulatedCrash() }; fail() } catch (_: SimulatedCrash) {}
        assertTrue(operation.isCommitted())
        assertEquals("新见域数据库", fixture.target.database.readText())
        var credentialReset = 0
        assertTrue(CompleteReplacement(fixture.target).recover { credentialReset++ })
        assertEquals(1, credentialReset)
        assertEquals("新见域数据库", fixture.target.database.readText())
        assertEquals("新附件", File(fixture.target.content, "user-profile/new.txt").readText())
        assertEquals("Life 不能变化", fixture.life.readText())
    }

    private fun fixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "replacement-synthetic-").toFile()
        val id = UUID.randomUUID().toString()
        val target = CompleteTarget(File(root, "databases/jianyu.sqlite"), File(root, "files/jianyu"), File(root, "noBackup/jianyu/full-transfer"), File(root, "noBackup/jianyu/content-epoch")) {
            name -> context.getSharedPreferences("replacement-$id-$name", 0)
        }
        target.database.apply { parentFile!!.mkdirs(); writeText("原见域数据库") }
        File(target.content, "user-profile/keep.txt").apply { parentFile!!.mkdirs(); writeText("旧附件") }
        target.epoch.apply { parentFile!!.mkdirs(); writeText(UUID.randomUUID().toString()) }
        target.preferences("jianyu_app_preferences").edit().putString("theme_mode", "SYSTEM").commit()
        val originalSettings = CompleteSettings.capture(target.preferences)
        val life = File(root, "files/life/keep.md").apply { parentFile!!.mkdirs(); writeText("Life 不能变化") }
        val staging = File(target.operations, "preview-$id").apply { mkdirs() }
        File(staging, "database.sqlite").writeText("新见域数据库")
        File(staging, "content/user-profile/new.txt").apply { parentFile!!.mkdirs(); writeText("新附件") }
        val settings = JSONObject(originalSettings).apply { getJSONObject("jianyu_app_preferences").put("theme_mode", JSONObject().put("type", "string").put("value", "DARK")) }
        CompleteSettings.write(File(staging, "settings.json"), settings.toString())
        val manifest = CompleteArchiveCodec.describe(staging, "com.elio.jianyu", "synthetic", CompleteDatabase.tables.associateWith { 0L }, emptyList())
        return Fixture(target, staging, manifest, life, originalSettings, target.epoch.readText())
    }
}
