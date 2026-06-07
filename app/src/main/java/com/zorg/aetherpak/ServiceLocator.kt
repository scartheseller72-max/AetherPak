package com.zorg.aetherpak

import android.content.Context
import com.zorg.aetherpak.access.AccessManager
import com.zorg.aetherpak.backup.AppScanner
import com.zorg.aetherpak.backup.BackupEngineImpl
import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.BackupEngine
import com.zorg.aetherpak.common.RestoreEngine
import com.zorg.aetherpak.compress.AetherArchive
import com.zorg.aetherpak.data.AetherDatabase
import com.zorg.aetherpak.data.BackupRepository
import com.zorg.aetherpak.data.SettingsStore
import com.zorg.aetherpak.restore.RestoreEngineImpl

/**
 * Manual dependency container. Engines and the scanner are bound to whichever [AccessProvider] is
 * currently selected; [setAccessProvider] rebuilds them. Provider-dependent accessors throw a
 * clear error when no provider has been selected so the UI can route the user to Access Setup.
 */
class ServiceLocator(context: Context) {

    val appContext: Context = context.applicationContext

    val accessManager: AccessManager = AccessManager(appContext)

    private val database: AetherDatabase = AetherDatabase.buildDatabase(appContext)

    val backupRepository: BackupRepository = BackupRepository(database.backupDao())

    val settingsStore: SettingsStore = SettingsStore(appContext)

    @Volatile
    private var provider: AccessProvider? = null

    @Volatile
    private var scanner: AppScanner? = null

    @Volatile
    private var backupEngine: BackupEngine? = null

    @Volatile
    private var restoreEngine: RestoreEngine? = null

    /** Currently selected provider, or null if none chosen yet. */
    val currentProvider: AccessProvider? get() = provider

    val hasProvider: Boolean get() = provider != null

    /** Rebuild scanner + engines for a freshly selected provider. */
    @Synchronized
    fun setAccessProvider(newProvider: AccessProvider) {
        provider = newProvider
        scanner = AppScanner(appContext, newProvider)
        backupEngine = BackupEngineImpl(appContext, newProvider, AetherArchive)
        restoreEngine = RestoreEngineImpl(appContext, newProvider, AetherArchive)
    }

    fun requireProvider(): AccessProvider =
        provider ?: error("No AccessProvider selected. Complete Access Setup first.")

    fun requireScanner(): AppScanner =
        scanner ?: error("No AppScanner: select an access mode in Access Setup first.")

    fun requireBackupEngine(): BackupEngine =
        backupEngine ?: error("No BackupEngine: select an access mode in Access Setup first.")

    fun requireRestoreEngine(): RestoreEngine =
        restoreEngine ?: error("No RestoreEngine: select an access mode in Access Setup first.")
}
