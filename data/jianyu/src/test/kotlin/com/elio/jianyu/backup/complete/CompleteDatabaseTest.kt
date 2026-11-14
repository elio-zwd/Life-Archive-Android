package com.elio.jianyu.backup.complete

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elio.jianyu.data.RoundtableDatabase
import com.elio.jianyu.domain.Character
import com.elio.jianyu.domain.ChatSession
import com.elio.jianyu.domain.Message
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteDatabaseTest {
    @Test fun oldUnassociatedConversationCustomRoleAndOwnedAudioRoundTripWithoutRenumbering() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "complete-database-").toFile()
        val file = createV18(staging)
        val audio = File(staging.parentFile, "synthetic-message.wav").apply { writeBytes(ByteArray(256) { it.toByte() }) }
        val database = Room.databaseBuilder(context, RoundtableDatabase::class.java, file.absolutePath).build()
        try {
            database.characterDao().insertCharacter(Character("custom", "自定义角色", "A", "说明", "完整提示词", "", 19, false, "[0.1]", "Kore"))
            database.chatDao().insertSession(ChatSession(id = 42, title = "未关联议题的旧会话", createdAt = 1234))
            database.chatDao().insertMessage(Message(id = 73, chatId = 42, senderId = "custom", senderName = "角色", avatar = "A", text = "完整正文 ```\n未闭合源码", timestamp = 2345, audioFilePath = audio.absolutePath, audioSizeBytes = audio.length(), audioFormat = "wav"))
            database.openHelper.writableDatabase.query("PRAGMA secure_delete=OFF").use { it.moveToFirst() }
            database.chatDao().insertMessage(Message(id = 74, chatId = 42, senderId = "custom", senderName = "角色", avatar = "A", text = "已经彻底删除的合成敏感内容_不应进入新备份", timestamp = 2346))
            database.openHelper.writableDatabase.execSQL("DELETE FROM messages WHERE id=74")
        } finally { database.close() }
        val before = CompleteDatabase.inspect(file)
        assertEquals(38, before.size)
        assertEquals(1L, before["characters"])
        assertEquals(1L, before["chat_sessions"])
        assertEquals(0L, before["issues"])
        CompleteDatabase.requireIdle(file)
        val originalDatabaseHash = CompleteArchiveCodec.sha256(file)
        val fingerprint = CompleteDatabase.fingerprint(file, "{}", File(staging, "content"))
        assertEquals(fingerprint, CompleteDatabase.fingerprint(file, "{}", File(staging, "content")))
        assertEquals(originalDatabaseHash, CompleteArchiveCodec.sha256(file))
        assertTrue(file.readBytes().toString(Charsets.UTF_8).contains("已经彻底删除的合成敏感内容_不应进入新备份"))
        CompleteDatabase.prepareExport(staging) { path -> audio.takeIf { it.absolutePath == path } }
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("已经彻底删除的合成敏感内容_不应进入新备份"))
        CompleteDatabase.inspect(file, before)
        CompleteDatabase.verifyFiles(staging, false)
        val content = File(staging.parentFile, "target-jianyu")
        CompleteDatabase.prepareImport(staging, content)
        CompleteDatabase.verifyFiles(staging, true, content)
        CompleteDatabase.inspect(file, before)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT id,chatId,text,audioFilePath FROM messages", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(73L, cursor.getLong(0)); assertEquals(42L, cursor.getLong(1))
                assertEquals("完整正文 ```\n未闭合源码", cursor.getString(2))
                assertTrue(File(cursor.getString(3)).toPath().startsWith(content.toPath()))
            }
        }
        val reopened = Room.databaseBuilder(context, RoundtableDatabase::class.java, file.absolutePath).build()
        try {
            assertTrue(reopened.chatDao().insertSession(ChatSession(title = "后续新会话", createdAt = 3000)) > 42)
            assertTrue(reopened.chatDao().insertMessage(Message(chatId = 42, senderId = "custom", senderName = "角色", avatar = "A", text = "后续消息", timestamp = 3001)) > 74)
        }
        finally { reopened.close() }
    }

    @Test fun forgedRoomIdentityCannotHideAlteredTablesOrExtraTriggers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "complete-shape-").toFile()
        val file = createV18(root)
        CompleteDatabase.inspect(file)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("ALTER TABLE characters ADD COLUMN unexpected TEXT") }
        try { CompleteDatabase.inspect(file); fail("实际结构不能只信任 Room 摘要") } catch (_: IllegalArgumentException) {}
    }

    private fun createV18(directory: File): File {
        val raw = requireNotNull(javaClass.getResourceAsStream("/legacy-schema-v18.json")).bufferedReader().use { it.readText() }
        CompleteDatabase.initializeSchema(raw)
        val schema = JSONObject(raw).getJSONObject("database")
        val file = File(directory, "database.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index); val name = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", name))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (i in 0 until indices.length()) database.execSQL(indices.getJSONObject(i).getString("createSql").replace("${'$'}{TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) database.execSQL(setup.getString(index))
            database.version = 18
        }
        return file
    }
}
