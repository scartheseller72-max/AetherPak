package com.zorg.aetherpak.restore

import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.AetherPaths
import com.zorg.aetherpak.common.BackupManifest
import com.zorg.aetherpak.common.ManifestEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encapsulates the ownership (chown) + SELinux (restorecon/chcon) repair loop that runs after
 * files have been laid down. This is the step that makes a restored app actually launch: the
 * private-data files must be owned by the app's NEW uid/gid (see [UidResolver]) and carry the
 * correct SELinux label (typically `u:object_r:app_data_file:s0:c<n>,c<n>`), which `restorecon`
 * derives from `file_contexts` for the path.
 */
class PermissionFixer(
    private val access: AccessProvider
) {

    data class FixReport(
        val chownedPaths: Int,
        val warnings: List<String>
    )

    /**
     * Fix ownership + SELinux for the supplied private-data [entries] of [pkg].
     *
     * @param newAppUid the uid resolved on THIS device (from [UidResolver.resolveAppUid]).
     * @param manifest source manifest providing [BackupManifest.sourceUid]/[BackupManifest.sourceGid].
     */
    suspend fun fixPrivateData(
        pkg: String,
        entries: List<ManifestEntry>,
        newAppUid: Int,
        manifest: BackupManifest
    ): FixReport = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        var chowned = 0

        if (!access.capabilities.canChangeOwnership) {
            warnings += "Ownership repair unavailable in ${access.mode} mode; restored private " +
                "data will be owned by the wrong uid and the app will likely crash on launch."
            return@withContext FixReport(0, warnings)
        }

        val dataDir = AetherPaths.privateData(pkg)

        // Per-entry, delta-remapped chown. Non-recursive per entry: every file/dir/symlink has its
        // own manifest record, so we set each precisely (symlinks chowned with -h via the provider).
        for (entry in entries) {
            val targetUid = UidResolver.remap(entry.uid, manifest.sourceUid, newAppUid)
            val targetGid = UidResolver.remap(entry.gid, manifest.sourceGid, newAppUid)
            val res = access.chown(entry.devicePath, targetUid, targetGid, recursive = false)
            if (res.isSuccess) {
                chowned++
            } else {
                warnings += "chown failed for ${entry.devicePath}: ${res.err.ifBlank { "code ${res.code}" }}"
            }
        }

        // Defensive sweep: a recursive remap of the whole tree to the app's base uid catches any
        // node we didn't have an explicit entry for (e.g. dirs the installer created).
        access.chown(dataDir, newAppUid, newAppUid, recursive = true)

        // SELinux: prefer restorecon (derives the correct context from file_contexts). Fall back to
        // an explicit chcon per entry only if restorecon is unsupported on this backend.
        if (access.capabilities.canRestoreSeContext) {
            val rc = access.restoreSeContext(dataDir, recursive = true)
            if (!rc.isSuccess) {
                warnings += "restorecon on $dataDir failed (${rc.err.ifBlank { "code ${rc.code}" }}); " +
                    "falling back to per-entry chcon."
                applyPerEntryContexts(entries, warnings)
            }
        } else {
            warnings += "restorecon unavailable in ${access.mode} mode; attempting per-entry chcon."
            applyPerEntryContexts(entries, warnings)
        }

        FixReport(chowned, warnings)
    }

    private suspend fun applyPerEntryContexts(entries: List<ManifestEntry>, warnings: MutableList<String>) {
        for (entry in entries) {
            val ctx = entry.seContext ?: continue
            val res = access.setSeContext(entry.devicePath, ctx, recursive = false)
            if (!res.isSuccess) {
                warnings += "chcon failed for ${entry.devicePath}: ${res.err.ifBlank { "code ${res.code}" }}"
            }
        }
    }
}
