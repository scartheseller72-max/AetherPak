package com.zorg.aetherpak.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zorg.aetherpak.AetherApp
import com.zorg.aetherpak.ServiceLocator
import com.zorg.aetherpak.ui.screens.backup.BackupViewModel
import com.zorg.aetherpak.ui.screens.detail.BackupDetailViewModel
import com.zorg.aetherpak.ui.screens.home.HomeViewModel
import com.zorg.aetherpak.ui.screens.restore.RestoreViewModel
import com.zorg.aetherpak.ui.screens.settings.SettingsViewModel
import com.zorg.aetherpak.ui.screens.setup.AccessSetupViewModel

/**
 * Single factory that wires every ViewModel from the [ServiceLocator]. Optional args (pkg / id)
 * are passed for the screens that need them.
 */
class AetherViewModelFactory(
    private val locator: ServiceLocator = AetherApp.locator(),
    private val pkg: String? = null,
    private val backupId: Long? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(locator) as T
            modelClass.isAssignableFrom(BackupViewModel::class.java) ->
                BackupViewModel(locator, requireNotNull(pkg) { "Backup requires pkg arg" }) as T
            modelClass.isAssignableFrom(RestoreViewModel::class.java) ->
                RestoreViewModel(locator) as T
            modelClass.isAssignableFrom(BackupDetailViewModel::class.java) ->
                BackupDetailViewModel(locator, requireNotNull(backupId) { "Detail requires id arg" }) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(locator) as T
            modelClass.isAssignableFrom(AccessSetupViewModel::class.java) ->
                AccessSetupViewModel(locator) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
