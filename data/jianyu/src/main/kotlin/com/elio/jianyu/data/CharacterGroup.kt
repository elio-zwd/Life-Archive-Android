package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterGroupDao {
    @Query("SELECT * FROM character_groups")
    fun getAllGroups(): Flow<List<CharacterGroup>>

    @Insert(entity = com.elio.jianyu.data.persistence.CharacterGroup::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: CharacterGroup)

    @Insert(entity = com.elio.jianyu.data.persistence.CharacterGroup::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(groups: List<CharacterGroup>)

    @Query("DELETE FROM character_groups WHERE id = :id")
    suspend fun deleteGroupById(id: String)

    @Query("SELECT * FROM character_groups WHERE id = :id LIMIT 1")
    suspend fun getGroupById(id: String): CharacterGroup?
}

class CharacterGroupRepository(private val characterGroupDao: CharacterGroupDao) {
    val allGroups: Flow<List<CharacterGroup>> = characterGroupDao.getAllGroups()

    suspend fun insert(group: CharacterGroup) = characterGroupDao.insertGroup(group)

    suspend fun insertAll(groups: List<CharacterGroup>) = characterGroupDao.insertAll(groups)

    suspend fun deleteById(id: String) = characterGroupDao.deleteGroupById(id)

    suspend fun getById(id: String) = characterGroupDao.getGroupById(id)
}
