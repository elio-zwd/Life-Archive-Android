package com.elio.lifearchive.data.notes

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "notes", indices = [Index(value = ["pathKey"], unique = true)])
internal data class NoteRecord(
    @androidx.room.PrimaryKey val id: String,
    val path: String,
    val pathKey: String,
    val version: String,
    val hash: String,
    val indexPending: Boolean = true,
    val pinned: Boolean = false,
    val readingOffset: Int = 0,
    val created: Long = 0,
    val modified: Long = 0,
    val deleted: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "'mountain'") val coverPreset: String = "mountain",
    val coverAttachment: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val timeUncertain: Boolean = false,
)

@Serializable
@Entity(tableName = "folders")
internal data class FolderRecord(@androidx.room.PrimaryKey val path: String)

@Serializable
@Entity(tableName = "organization_topics")
internal data class TopicRecord(@androidx.room.PrimaryKey val name: String, val position: Int)

@Dao
internal interface StateDao {
    @Query("SELECT * FROM organization_topics ORDER BY position, name") suspend fun topics(): List<TopicRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTopic(topic: TopicRecord)
    @Query("DELETE FROM organization_topics") suspend fun clearTopics()
    @Query("DELETE FROM organization_topics WHERE name = :name") suspend fun removeTopic(name: String)
    @Query("SELECT * FROM notes ORDER BY pathKey") suspend fun all(): List<NoteRecord>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun find(id: String): NoteRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: NoteRecord)
    @Query("DELETE FROM notes") suspend fun clear()
    @Query("DELETE FROM notes WHERE id = :id") suspend fun remove(id: String)
    @Query("SELECT * FROM folders ORDER BY path") suspend fun folders(): List<FolderRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFolder(folder: FolderRecord)
    @Query("DELETE FROM folders") suspend fun clearFolders()
    @Query("DELETE FROM folders WHERE path = :path") suspend fun removeFolder(path: String)
    @Query("UPDATE notes SET indexPending = 1") suspend fun invalidateIndex()
    @Transaction suspend fun replace(records: List<NoteRecord>) { clear(); records.forEach { put(it.copy(indexPending = true)) } }
}

@Database(entities = [NoteRecord::class, FolderRecord::class, TopicRecord::class], version = 4, exportSchema = true)
internal abstract class StateDatabase : RoomDatabase() { abstract fun notes(): StateDao }

@Entity(tableName = "projections")
internal data class IndexRecord(@androidx.room.PrimaryKey val id: String, val version: String, val rawText: String)

@Dao
internal interface IndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: IndexRecord)
    @Query("DELETE FROM projections") suspend fun clear()
    @Query("SELECT * FROM projections WHERE id = :id") suspend fun find(id: String): IndexRecord?
    @Query("SELECT * FROM projections") suspend fun all(): List<IndexRecord>
    @Query("DELETE FROM projections WHERE id = :id") suspend fun remove(id: String)
}

@Database(entities = [IndexRecord::class], version = 1, exportSchema = true)
internal abstract class IndexDatabase : RoomDatabase() { abstract fun projections(): IndexDao }
