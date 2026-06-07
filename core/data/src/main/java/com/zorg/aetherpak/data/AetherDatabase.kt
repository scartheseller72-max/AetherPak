package com.zorg.aetherpak.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BackupRecord::class], version = 1, exportSchema = false)
abstract class AetherDatabase : RoomDatabase() {

    abstract fun backupDao(): BackupDao

    companion object {
        private const val DB_NAME = "aetherpak.db"

        @Volatile
        private var INSTANCE: AetherDatabase? = null

        fun buildDatabase(context: Context): AetherDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AetherDatabase::class.java,
                    DB_NAME
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
