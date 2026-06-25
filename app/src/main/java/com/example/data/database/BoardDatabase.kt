package com.example.data.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "boards")
data class BoardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val folder: String = "All Boards",
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val contentJson: String = "[]",
    val thumbnailPath: String? = null
)

@Dao
interface BoardDao {
    @Query("SELECT * FROM boards ORDER BY updatedAt DESC")
    fun getAllBoards(): Flow<List<BoardEntity>>
    
    @Query("SELECT * FROM boards WHERE folder = :folder ORDER BY updatedAt DESC")
    fun getBoardsByFolder(folder: String): Flow<List<BoardEntity>>
    
    @Query("SELECT * FROM boards WHERE id = :id")
    suspend fun getBoardById(id: Long): BoardEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBoard(board: BoardEntity): Long
    
    @Query("DELETE FROM boards WHERE id = :id")
    suspend fun deleteBoardById(id: Long)
    
    @Query("UPDATE boards SET name = :name, folder = :folder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBoardMetadata(id: Long, name: String, folder: String, updatedAt: Long)
    
    @Query("UPDATE boards SET contentJson = :contentJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBoardContent(id: Long, contentJson: String, updatedAt: Long)
    
    @Query("UPDATE boards SET isLocked = :isLocked, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBoardLockState(id: Long, isLocked: Boolean, updatedAt: Long)
}

@Database(entities = [BoardEntity::class], version = 1, exportSchema = false)
abstract class BoardDatabase : RoomDatabase() {
    abstract fun boardDao(): BoardDao
    
    companion object {
        @Volatile
        private var INSTANCE: BoardDatabase? = null
        
        fun getDatabase(context: Context): BoardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BoardDatabase::class.java,
                    "boardly_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
