package com.elio.jianyu.backup.complete

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.elio.jianyu.JianyuAppRuntimeProvider
import com.elio.jianyu.domain.ChatSession
import com.elio.jianyu.runtime.DatabaseMaintenanceOutcome
import com.elio.jianyu.runtime.DatabaseMaintenanceStage
import com.elio.jianyu.runtime.JianyuRuntimeState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteRuntimeRecoveryTest {
    @Test fun retry() = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        WorkManager.initialize(app, Configuration.Builder().build())
        val target = CompleteTransferRecovery.target(app)
        CompleteDatabase.initialize(app)
        val id = JianyuAppRuntimeProvider.withRuntime(app) { runtime ->
            runtime.database.chatDao().insertSession(ChatSession(title = "恢复前的会话", createdAt = 1000))
        }
        val life = File(app.filesDir, "Life-sentinel.md").apply { writeText("Life 原内容") }
        val replacement = CompleteReplacement(target)
        val outcome = JianyuAppRuntimeProvider.withDatabaseClosed(app,
            beforeClose = { database -> database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) } },
            whileClosed = { original ->
                val staging = File(target.operations, "preview-${UUID.randomUUID()}").apply { mkdirs() }
                val copied = File(staging, "database.sqlite")
                CompleteDatabase.copySynced(original, copied)
                CompleteSettings.write(File(staging, "settings.json"), CompleteSettings.capture(target.preferences))
                SQLiteDatabase.openDatabase(copied.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE chat_sessions SET title='尚未确认的新会话' WHERE id=?", arrayOf<Any>(id))
                }
                val manifest = CompleteArchiveCodec.describe(staging, "com.elio.jianyu", "synthetic", CompleteDatabase.inspect(copied), emptyList())
                replacement.begin(staging, manifest)
            },
            afterReopen = { error("synthetic_post_reopen_verification_failure") },
        )
        assertTrue(outcome is DatabaseMaintenanceOutcome.Failure && outcome.stage == DatabaseMaintenanceStage.AFTER_REOPEN)
        assertTrue(JianyuAppRuntimeProvider.states().value is JianyuRuntimeState.Unavailable)
        assertTrue(replacement.hasPending())
        // 模拟下次显式打开：先回滚日志，再建立和公开可用 Room 运行实例。
        assertTrue(JianyuAppRuntimeProvider.retryOpen(app))
        assertFalse(replacement.hasPending())
        JianyuAppRuntimeProvider.withRuntime(app) { runtime ->
            runtime.database.openHelper.readableDatabase.query("SELECT title FROM chat_sessions WHERE id=?", arrayOf<Any>(id)).use {
                assertTrue(it.moveToFirst()); assertEquals("恢复前的会话", it.getString(0))
            }
        }
        assertEquals("Life 原内容", life.readText())
        Unit
    }
}
