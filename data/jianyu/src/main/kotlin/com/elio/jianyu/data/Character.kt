package com.elio.jianyu.data

import com.elio.jianyu.domain.*
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY `order` ASC")
    fun getAllCharacters(): Flow<List<Character>>

    @Query("SELECT * FROM characters WHERE id = :id LIMIT 1")
    suspend fun getCharacterById(id: String): Character?

    @Insert(entity = com.elio.jianyu.data.persistence.Character::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacter(character: Character)

    @Insert(entity = com.elio.jianyu.data.persistence.Character::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(characters: List<Character>)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteCharacterById(id: String)
}

class CharacterRepository(private val characterDao: CharacterDao) {
    val allCharacters: Flow<List<Character>> = characterDao.getAllCharacters()

    suspend fun getCharacterById(id: String) = characterDao.getCharacterById(id)

    suspend fun insert(character: Character) = characterDao.insertCharacter(character)

    suspend fun insertAll(characters: List<Character>) = characterDao.insertAll(characters)

    suspend fun deleteById(id: String) = characterDao.deleteCharacterById(id)
}
