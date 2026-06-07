package com.zorg.aetherpak.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.zorg.aetherpak.ui.screens.backup.BackupScreen
import com.zorg.aetherpak.ui.screens.detail.BackupDetailScreen
import com.zorg.aetherpak.ui.screens.home.HomeScreen
import com.zorg.aetherpak.ui.screens.restore.RestoreScreen
import com.zorg.aetherpak.ui.screens.settings.SettingsScreen
import com.zorg.aetherpak.ui.screens.setup.AccessSetupScreen

@Composable
fun AetherNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {

        composable(Screen.Home.route) {
            HomeScreen(
                onAppClick = { pkg -> navController.navigate(Screen.Backup.create(pkg)) },
                onRestore = { navController.navigate(Screen.Restore.route) },
                onSettings = { navController.navigate(Screen.Settings.route) },
                onAccessSetup = { navController.navigate(Screen.AccessSetup.route) }
            )
        }

        composable(
            route = Screen.Backup.route,
            arguments = listOf(navArgument(Screen.Backup.ARG_PKG) { type = NavType.StringType })
        ) { entry ->
            val pkg = entry.arguments?.getString(Screen.Backup.ARG_PKG).orEmpty()
            BackupScreen(
                pkg = pkg,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() }
            )
        }

        composable(Screen.Restore.route) {
            RestoreScreen(
                onBack = { navController.popBackStack() },
                onOpenDetail = { id -> navController.navigate(Screen.BackupDetail.create(id)) }
            )
        }

        composable(
            route = Screen.BackupDetail.route,
            arguments = listOf(navArgument(Screen.BackupDetail.ARG_ID) { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong(Screen.BackupDetail.ARG_ID) ?: 0L
            BackupDetailScreen(
                backupId = id,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAccessSetup = { navController.navigate(Screen.AccessSetup.route) }
            )
        }

        composable(Screen.AccessSetup.route) {
            AccessSetupScreen(onBack = { navController.popBackStack() })
        }
    }
}
