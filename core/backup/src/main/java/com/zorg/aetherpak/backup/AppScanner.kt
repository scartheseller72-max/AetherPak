package com.zorg.aetherpak.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.AetherPaths
import com.zorg.aetherpak.common.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Enumerates installed applications via [PackageManager] and enriches each with the on-device
 * paths the backup engine needs (base/split APKs, private data dir, OBB and external-data dirs).
 *
 * The [AccessProvider] is consulted only for lightweight existence checks (e.g. whether an OBB
 * directory is present); the heavy reading happens later in the engine.
 */
class AppScanner(
    private val context: Context,
    private val access: AccessProvider
) {

    private val pm: PackageManager get() = context.packageManager

    /**
     * Enumerate installed packages. System apps are filtered out unless [includeSystem] is true.
     * Updated system apps (UPDATED_SYSTEM_APP) are always treated as user-relevant.
     */
    suspend fun scanInstalledApps(includeSystem: Boolean = false): List<AppEntry> =
        withContext(Dispatchers.IO) {
            val packages = queryAllPackages()
            val out = ArrayList<AppEntry>(packages.size)
            for (info in packages) {
                coroutineContext.ensureActive()
                val appInfo = info.applicationInfo ?: continue
                val isSystem = isSystemApp(appInfo)
                if (isSystem && !includeSystem) continue
                out.add(toAppEntry(info, appInfo, isSystem))
            }
            out.sortedBy { it.label.lowercase() }
        }

    /** Scan a single package by name; returns null when the package is not installed. */
    suspend fun scanSingle(pkg: String): AppEntry? = withContext(Dispatchers.IO) {
        val info = runCatching { getPackageInfo(pkg) }.getOrNull() ?: return@withContext null
        val appInfo = info.applicationInfo ?: return@withContext null
        toAppEntry(info, appInfo, isSystemApp(appInfo))
    }

    private fun queryAllPackages(): List<PackageInfo> {
        val flags = PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
    }

    private fun getPackageInfo(pkg: String): PackageInfo {
        val flags = PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        val systemFlag = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        // An updated system app behaves like a user app for backup purposes.
        return systemFlag && !updatedSystem
    }

    private suspend fun toAppEntry(
        info: PackageInfo,
        appInfo: ApplicationInfo,
        isSystem: Boolean
    ): AppEntry {
        val pkg = info.packageName
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(pkg)
        val versionName = info.versionName ?: ""
        val versionCode = longVersionCode(info)

        val baseApkPath = appInfo.sourceDir ?: ""
        val splitApkPaths = appInfo.splitSourceDirs?.filterNotNull() ?: emptyList()
        val dataDir = appInfo.dataDir ?: AetherPaths.privateData(pkg)

        val obbDir = AetherPaths.obbDir(pkg)
        val externalDataDir = AetherPaths.externalDataDir(pkg)
        val hasObb = runCatching { access.exists(obbDir) }.getOrDefault(false)

        val totalApkBytes = sumApkBytes(baseApkPath, splitApkPaths)

        return AppEntry(
            packageName = pkg,
            label = label,
            versionName = versionName,
            versionCode = versionCode,
            isSystemApp = isSystem,
            baseApkPath = baseApkPath,
            splitApkPaths = splitApkPaths,
            dataDir = dataDir,
            hasObb = hasObb,
            obbDir = obbDir,
            externalDataDir = externalDataDir,
            uid = appInfo.uid,
            totalApkBytes = totalApkBytes,
            iconBase64 = null
        )
    }

    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /** APK paths are world-readable, so plain File access suffices and avoids elevation cost. */
    private fun sumApkBytes(baseApkPath: String, splitApkPaths: List<String>): Long {
        var total = 0L
        if (baseApkPath.isNotEmpty()) {
            total += File(baseApkPath).let { if (it.isFile) it.length() else 0L }
        }
        for (split in splitApkPaths) {
            total += File(split).let { if (it.isFile) it.length() else 0L }
        }
        return total
    }
}
