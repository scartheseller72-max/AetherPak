package com.zorg.aetherpak.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BackupRecord): Long

    @Delete
    suspend fun delete(record: BackupRecord)

    @Query("DELETE FROM backups WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM backups ORDER BY createdAtEpochMs DESC")
    fun getAll(): Flow<List<BackupRecord>>

    @Query("SELECT * FROM backups WHERE packageName = :pkg ORDER BY createdAtEpochMs DESC")
    fun getByPackage(pkg: String): Flow<List<BackupRecord>>

    @Query("SELECT * FROM backups WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BackupRecord?
}
