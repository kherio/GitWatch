package com.kherio.gitwatch.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedDao {
    @Query("SELECT * FROM tracked")
    fun observeAll(): Flow<List<TrackedItem>>

    @Query("SELECT * FROM tracked")
    suspend fun all(): List<TrackedItem>

    @Query("SELECT * FROM tracked WHERE kind = :kind AND identifier = :identifier LIMIT 1")
    suspend fun find(kind: String, identifier: String): TrackedItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TrackedItem): Long

    @Update
    suspend fun update(item: TrackedItem)

    @Delete
    suspend fun delete(item: TrackedItem)
}

@Database(entities = [TrackedItem::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TrackedDao
}
