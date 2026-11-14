package com.elio.jianyu.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elio.jianyu.domain.Character
import com.elio.jianyu.domain.ChatSession
import com.elio.jianyu.domain.Message
import com.elio.jianyu.domain.MessageAnswerStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DomainRoomBoundaryTest {
    @Test fun originalV18SchemaOpensWithoutMigrationAndPreservesDomainValues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "jianyu_original_v18_boundary"
        context.deleteDatabase(name)
        val original = JSONObject(requireNotNull(javaClass.getResourceAsStream("/legacy-schema-v18.json"))
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            val entities = original.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                database.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (i in 0 until indices.length()) {
                    database.execSQL(indices.getJSONObject(i).getString("createSql")
                        .replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                }
            }
            val setup = original.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) database.execSQL(setup.getString(index))
            database.version = 18
        }
        val database = Room.databaseBuilder(context, RoundtableDatabase::class.java, name)
            .addMigrations(*RoundtableDatabase.ALL_MIGRATIONS).build()
        try {
            val sessionId = database.chatDao().insertSession(ChatSession(title = "旧会话：中文与 YAML ---", createdAt = 1234))
            assertTrue("普通模型必须沿用自增主键语义", sessionId > 0)
            val character = Character("custom", "自定义角色", "A", "说明", "提示词\n原文", "", 7,
                isActive = false, skillDescriptionVector = "[0.1,0.2]", voiceConfig = "Kore")
            database.characterDao().insertCharacter(character)
            assertEquals(character, database.characterDao().getCharacterById("custom"))
            val message = Message(chatId = sessionId, senderId = "custom", senderName = "角色", avatar = "A",
                text = "未完代码：```kotlin\nval 测试 = 1", timestamp = 4567, answerStatus = MessageAnswerStatus.STOPPED,
                audioFilePath = "audio/example.wav", audioFormat = "wav", audioSizeBytes = 42,
                conversationContextSnapshotIds = "[]")
            val messageId = database.chatDao().insertMessage(message)
            assertTrue(messageId > 0)
            assertEquals(message.copy(id = messageId), database.chatDao().getMessagesForChat(sessionId).single())
            val partial = message.copy(id = 0, text = "已经持久化的半段回答", isPending = true, answerStatus = MessageAnswerStatus.GENERATING)
            val partialId = database.chatDao().insertMessage(partial)
            database.chatDao().markPendingInterrupted()
            val interrupted = database.chatDao().getMessagesForChat(sessionId).first { it.id == partialId }
            assertEquals(partial.text, interrupted.text)
            assertEquals(MessageAnswerStatus.STOPPED, interrupted.answerStatus)
            assertFalse(interrupted.isPending)
            assertEquals("旧会话：中文与 YAML ---", database.chatDao().getSessionById(sessionId)?.title)
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
