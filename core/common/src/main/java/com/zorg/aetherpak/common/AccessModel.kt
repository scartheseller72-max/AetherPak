package com.zorg.aetherpak.common

/**
 * Privilege backend used to reach protected paths.
 *
 * The whole-data backup problem is gated by the Android app sandbox:
 *  - ROOT    : a real superuser shell (Magisk / KernelSU via libsu). Can read & write any
 *              app's private /data/data/<pkg>, change ownership (chown) and SELinux labels
 *              (restorecon). This is the only mode capable of a FULL backup.
 *  - SHIZUKU : ADB shell privileges (UID 2000) brokered by the Shizuku service. Shell CANNOT
 *              read another app's private /data/data directory — that is the sandbox boundary.
 *              Shizuku can install/uninstall packages and read OBB + shared media. PARTIAL only.
 *  - NONE    : no elevation available.
 */
enum class AccessMode {
    ROOT,
    SHIZUKU,
    NONE
}

/**
 * Truthful, per-provider capability matrix. The UI reads this to decide which backup
 * components it may offer, and to surface honest warnings (e.g. "private data unavailable
 * in Shizuku mode"). Never claim a capability the backend does not actually have.
 */
data class AccessCapabilities(
    val mode: AccessMode,
    /** Can read another package's private /data/data/<pkg> tree. ROOT only. */
    val canReadPrivateData: Boolean,
    /** Can write into another package's private /data/data/<pkg> tree. ROOT only. */
    val canWritePrivateData: Boolean,
    /** Can chown -R restored files to a new UID/GID. ROOT only. */
    val canChangeOwnership: Boolean,
    /** Can restorecon -R restored files to fix SELinux contexts. ROOT only. */
    val canRestoreSeContext: Boolean,
    /** Can `pm install` / session-install an APK. ROOT and SHIZUKU. */
    val canInstallPackages: Boolean,
    /** Can read OBB (/sdcard/Android/obb) and shared media. ROOT and SHIZUKU. */
    val canReadObb: Boolean
) {
    /** True when this mode can produce a full backup including private user data. */
    val supportsFullBackup: Boolean get() = canReadPrivateData

    companion object {
        fun root() = AccessCapabilities(
            mode = AccessMode.ROOT,
            canReadPrivateData = true,
            canWritePrivateData = true,
            canChangeOwnership = true,
            canRestoreSeContext = true,
            canInstallPackages = true,
            canReadObb = true
        )

        fun shizuku() = AccessCapabilities(
            mode = AccessMode.SHIZUKU,
            canReadPrivateData = false,
            canWritePrivateData = false,
            canChangeOwnership = false,
            canRestoreSeContext = false,
            canInstallPackages = true,
            canReadObb = true
        )

        fun none() = AccessCapabilities(
            mode = AccessMode.NONE,
            canReadPrivateData = false,
            canWritePrivateData = false,
            canChangeOwnership = false,
            canRestoreSeContext = false,
            canInstallPackages = false,
            canReadObb = false
        )
    }
}
