package com.zorg.aetherpak.data

import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.common.BackupOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Maps engine outcomes into persisted [BackupRecord] rows and exposes the catalog to the UI.
 */
class BackupRepository(private val dao: BackupDao) {

    fun observeAll(): Flow<List<BackupRecord>> = dao.getAll()

    fun observeByPackage(pkg: String): Flow<List<BackupRecord>> = dao.getByPackage(pkg)

    suspend fun getById(id: Long): BackupRecord? = dao.getById(id)

    /** Persist a successful backup, deriving the catalog row from its manifest. */
    suspend fun record(outcome: BackupOutcome, accessMode: AccessMode): Long {
        val manifest = outcome.manifest
        val row = BackupRecord(
            packageName = manifest.packageName,
            appLabel = manifest.appLabel,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            archivePath = outcome.archivePath,
            archiveSizeBytes = outcome.totalBytes,
            codec = manifest.codec,
            accessMode = accessMode.name,
            isPartial = manifest.isPartial,
            createdAtEpochMs = manifest.createdAtEpochMs
        )
        return dao.insert(row)
    }

    suspend fun remove(id: Long) = dao.deleteById(id)
}
