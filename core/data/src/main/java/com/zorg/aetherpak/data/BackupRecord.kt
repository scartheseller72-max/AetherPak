package com.zorg.aetherpak.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted row describing a `.ark` archive produced by the backup engine. The catalog is the
 * source of truth for the Restore picker and the BackupDetail screen.
 */
@Entity(tableName = "backups")
data class BackupRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val archivePath: String,
    val archiveSizeBytes: Long,
    val codec: String,
    val accessMode: String,
    val isPartial: Boolean,
    val createdAtEpochMs: Long
)
