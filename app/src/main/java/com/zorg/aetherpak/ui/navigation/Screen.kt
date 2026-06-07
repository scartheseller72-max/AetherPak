package com.zorg.aetherpak.ui.navigation

/** Type-safe route catalog for the app. */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Backup : Screen("backup/{pkg}") {
        const val ARG_PKG = "pkg"
        fun create(pkg: String) = "backup/$pkg"
    }
    data object Restore : Screen("restore")
    data object BackupDetail : Screen("backupDetail/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "backupDetail/$id"
    }
    data object Settings : Screen("settings")
    data object AccessSetup : Screen("accessSetup")
}
