package com.elio.jianyu.backup.complete

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 夹具由旧见域真实运行实例导出端生成，不能用本模块写入器重新生成来替代互通证据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceExportInteropTest {
    @Test fun oldApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "source-interop-").toFile()
        val manifest = requireNotNull(javaClass.getResourceAsStream("/source-export-v18.jyfull")).use {
            CompleteArchiveCodec.read(it, "合成完整迁出的测试密码123", directory)
        }
        CompleteDatabase.initialize(context)
        assertEquals("com.elio.jianyu", manifest.sourcePackage)
        assertEquals(1L, manifest.rows["messages"])
        CompleteDatabase.inspect(File(directory, "database.sqlite"), manifest.rows)
        CompleteDatabase.requireIdle(File(directory, "database.sqlite"))
        CompleteSettings.validate(File(directory, "settings.json").readText())
        CompleteDatabase.verifyFiles(directory, false)
        val targetContent = File(directory.parentFile, "new-jianyu-content")
        CompleteDatabase.prepareImport(directory, targetContent)
        CompleteDatabase.verifyFiles(directory, true, targetContent)
        SQLiteDatabase.openDatabase(File(directory, "database.sqlite").absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,text,answerStatus,audioFilePath FROM messages", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(101L, rows.getLong(0))
                assertEquals("已经保存的半段回答", rows.getString(1))
                assertEquals("STOPPED", rows.getString(2))
                assertTrue(File(rows.getString(3)).toPath().startsWith(targetContent.toPath()))
            }
            db.rawQuery("SELECT systemPrompt FROM characters WHERE id='transfer-custom'", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals("完整自定义提示词", rows.getString(0))
            }
        }
        assertEquals("用户原文件", File(directory, "content/user-profile/keep.txt").readText())
    }
}
