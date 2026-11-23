package com.elio.lifearchive.data.pet

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "overlay")
internal data class OverlayRow(@PrimaryKey val id: Int = 1, val settings: String = "{}")

@Entity(tableName = "profile")
internal data class ProfileRow(
    @PrimaryKey val id: Int = 1, val name: String, val personality: String,
    val draft: String = "", val showInApp: Boolean = false,
)
@Entity(tableName = "messages", indices = [Index("operationId"), Index("createdAt")])
internal data class MessageRow(
    @PrimaryKey val id: String, val operationId: String, val role: String, val text: String,
    val status: String, val createdAt: Long, val emotion: String = "CALM",
    val sourceMessageId: String? = null, val error: String? = null,
)
@Entity(tableName = "memories")
internal data class MemoryRow(
    @PrimaryKey val id: String, val text: String, val sourceMessageId: String?, val revision: Long = 1,
)

@Dao
internal interface CompanionDao {
    @Query("SELECT settings FROM overlay WHERE id = 1") fun overlay(): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun overlay(row: OverlayRow)
    @Query("SELECT * FROM profile WHERE id = 1") fun profile(): ProfileRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun initialize(row: ProfileRow)
    @Query("UPDATE profile SET name = :name, personality = :personality WHERE id = 1") fun profile(name: String, personality: String)
    @Query("UPDATE profile SET draft = :text WHERE id = 1") fun draft(text: String)
    @Query("UPDATE profile SET showInApp = :visible WHERE id = 1") fun visible(visible: Boolean)
    @Query("SELECT * FROM messages ORDER BY createdAt, rowid") fun messages(): List<MessageRow>
    @Query("SELECT * FROM messages WHERE id = :id") fun message(id: String): MessageRow?
    @Query("SELECT COUNT(*) FROM messages WHERE operationId = :operation") fun operationExists(operation: String): Int
    @Insert fun insert(vararg messages: MessageRow)
    @Update fun update(message: MessageRow)
    @Query("UPDATE messages SET status = 'INTERRUPTED', error = '上次回应因应用退出而中断，可重试。' WHERE status = 'GENERATING'") fun interrupt()
    @Query("SELECT * FROM memories ORDER BY rowid") fun memories(): List<MemoryRow>
    @Insert fun remember(memory: MemoryRow)
    @Query("UPDATE memories SET text = :text, revision = revision + 1 WHERE id = :id AND revision = :revision") fun edit(id: String, revision: Long, text: String): Int
    @Query("DELETE FROM memories WHERE id = :id AND revision = :revision") fun delete(id: String, revision: Long): Int
    @Query("SELECT COUNT(*) FROM messages") fun changes(): Flow<Int>
}

@Database(entities = [ProfileRow::class, MessageRow::class, MemoryRow::class, OverlayRow::class], version = 2, exportSchema = true)
internal abstract class CompanionDatabase : RoomDatabase() {
    abstract fun companion(): CompanionDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `overlay` (`id` INTEGER NOT NULL, `settings` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}
