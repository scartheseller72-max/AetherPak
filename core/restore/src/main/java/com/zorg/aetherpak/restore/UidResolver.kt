package com.zorg.aetherpak.restore

import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.AetherPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the Linux UID an app receives **on this device after install**, and remaps the
 * UID/GID values recorded in the backup manifest onto it.
 *
 * ## Why this exists (the single most correctness-critical piece of restore)
 *
 * Android assigns every installed app a private Linux UID in the range 10000+ (`AID_APP_START`).
 * That number is allocated sequentially as packages are installed, so the *same* package gets a
 * *different* UID on a different device (e.g. 10117 on the old phone, 10234 on the new one). The
 * files we restore into `/data/data/<pkg>` were captured with the **source** ownership baked in
 * (`ManifestEntry.uid`/`gid`, anchored by `BackupManifest.sourceUid`). If we drop those files back
 * with the old owner, the app — now running as a different UID — cannot read its own data and the
 * kernel/SELinux denies access, crashing it on launch.
 *
 * ## The delta remap (TWRP / Neo Backup style)
 *
 *   delta        = newAppUid - sourceBaseUid
 *   remappedUid  = originalUid + delta            (only when originalUid is in the app range)
 *
 * Equivalently `original - sourceBaseUid + newBaseUid`. Applying the *delta* (rather than blindly
 * forcing every file to `newAppUid`) preserves intra-app offsets. The most common offset is the
 * per-app cache GID `AID_CACHE = AID_APP + 100000` (e.g. uid 10234 -> cache gid 1037234): files
 * owned by that derived gid shift by the same delta and stay consistent. UIDs outside the app
 * range (shared system gids such as `AID_SDCARD_RW`, `AID_MEDIA_RW`, root-owned nodes) are left
 * untouched.
 */
object UidResolver {

    /** Android per-user app UID window. AID_APP_START..AID_APP_END within a user. */
    const val AID_APP_START = 10000
    const val AID_APP_END = 19999

    /**
     * Returns true when [uid] lives in the per-app range for any Android user. Android encodes the
     * user id as `uid / 100000`, so the app portion is `uid % 100000`; we test that against the
     * app window. This keeps the remap correct for secondary-profile UIDs too.
     */
    fun isAppUid(uid: Int): Boolean {
        if (uid < 0) return false
        val appPortion = uid % 100_000
        return appPortion in AID_APP_START..AID_APP_END
    }

    /**
     * Delta remap. If [originalUid] is inside the app range, shift it by
     * `newBaseUid - sourceBaseUid`; otherwise return it unchanged (shared/system ids, cache gids
     * outside the window, root-owned files).
     */
    fun remap(originalUid: Int, sourceBaseUid: Int, newBaseUid: Int): Int {
        if (!isAppUid(originalUid)) return originalUid
        val delta = newBaseUid - sourceBaseUid
        val result = originalUid + delta
        // Guard against a nonsensical negative result from a malformed manifest.
        return if (result < 0) newBaseUid else result
    }

    /**
     * Read the UID the package actually holds on THIS device after install. Tries, in order of
     * reliability:
     *   1. /data/system/packages.list — authoritative; line is
     *        "<pkg> <uid> <debugFlag> <dataPath> <seinfo> <gids...>"   (uid is field index 1)
     *   2. stat -c %u /data/data/<pkg>                                 (owner of the data dir)
     *   3. dumpsys package <pkg> | grep userId=                        (last resort, parser-fragile)
     *
     * Returns -1 only if every strategy fails (caller must treat that as PERMISSION_FIX_FAILED).
     */
    suspend fun resolveAppUid(access: AccessProvider, pkg: String): Int = withContext(Dispatchers.IO) {
        // `pkg` originates from the untrusted archive manifest and is interpolated into root
        // shell commands below. A valid Android package name is only [A-Za-z0-9._]; reject
        // anything else outright so a crafted manifest can't inject a command.
        if (!isValidPackageName(pkg)) return@withContext -1
        parseFromPackagesList(access, pkg)
            .takeIf { it > 0 }
            ?: parseFromStat(access, pkg).takeIf { it > 0 }
            ?: parseFromDumpsys(access, pkg).takeIf { it > 0 }
            ?: -1
    }

    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9._]+$")

    /** A defensive check matching Android's own package-name character set. */
    fun isValidPackageName(pkg: String): Boolean =
        pkg.isNotEmpty() && pkg.length <= 255 && PACKAGE_NAME_REGEX.matches(pkg)

    /** Single-quote a value for safe shell interpolation. */
    private fun shellQuote(s: String): String =
        if (s.isEmpty()) "''" else "'" + s.replace("'", "'\\''") + "'"

    private suspend fun parseFromPackagesList(access: AccessProvider, pkg: String): Int {
        // grep the exact package token at line start; fall back to a plain cat if grep is absent.
        val res = access.exec("grep -E '^${escapeForGrep(pkg)} ' /data/system/packages.list")
        val line = res.stdout.firstOrNull { it.isNotBlank() }
            ?: run {
                val cat = access.exec("cat /data/system/packages.list")
                cat.stdout.firstOrNull { l ->
                    val tok = l.trim().substringBefore(' ')
                    tok == pkg
                }
            }
            ?: return -1
        val fields = line.trim().split(Regex("\\s+"))
        // fields[0] = package name, fields[1] = uid
        if (fields.size < 2 || fields[0] != pkg) return -1
        return fields[1].toIntOrNull() ?: -1
    }

    private suspend fun parseFromStat(access: AccessProvider, pkg: String): Int {
        val dataDir = AetherPaths.privateData(pkg)
        val res = access.exec("stat -c %u ${shellQuote(dataDir)}")
        if (!res.isSuccess) return -1
        return res.stdout.firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull() ?: -1
    }

    private suspend fun parseFromDumpsys(access: AccessProvider, pkg: String): Int {
        val res = access.exec("dumpsys package ${shellQuote(pkg)}")
        if (!res.isSuccess) return -1
        // Look for a token like "userId=10234".
        val match = res.stdout.firstNotNullOfOrNull { line ->
            Regex("userId=(\\d+)").find(line)?.groupValues?.getOrNull(1)
        }
        return match?.toIntOrNull() ?: -1
    }

    /** Escape regex-special characters in a package name for safe use inside a grep ERE. */
    private fun escapeForGrep(pkg: String): String =
        pkg.replace(".", "\\.")
}
