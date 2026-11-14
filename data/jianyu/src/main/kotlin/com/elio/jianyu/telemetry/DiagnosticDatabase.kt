package com.elio.jianyu.telemetry

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "exchanges", indices = [Index("timestamp"), Index("operationId")])
data class DiagnosticRow(@PrimaryKey val id: String, val timestamp: Long, val operationId: String, val metadata: String)

@Dao
interface DiagnosticDao {
    @Query("SELECT * FROM exchanges ORDER BY timestamp DESC LIMIT 200") fun observe(): Flow<List<DiagnosticRow>>
    @Query("SELECT COUNT(*) FROM exchanges") fun observeCount(): Flow<Int>
    @Query("SELECT * FROM exchanges ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset") fun page(limit: Int, offset: Int): List<DiagnosticRow>
    @Query("SELECT * FROM exchanges WHERE id = :id") fun get(id: String): DiagnosticRow?
    @Query("SELECT * FROM exchanges WHERE operationId = :id ORDER BY timestamp") fun operation(id: String): List<DiagnosticRow>
    @Query("SELECT * FROM exchanges WHERE metadata LIKE '%\"captureState\":\"RECORDING\"%'") fun pending(): List<DiagnosticRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: DiagnosticRow)
    @Query("DELETE FROM exchanges WHERE id IN (:ids)") fun delete(ids: List<String>)
    @Query("DELETE FROM exchanges") fun clear()
}

/** 独立于会话数据库，日志升级不改变用户业务数据。 */
@Database(entities = [DiagnosticRow::class], version = 1, exportSchema = true)
abstract class DiagnosticDatabase : RoomDatabase() { abstract fun exchanges(): DiagnosticDao }
