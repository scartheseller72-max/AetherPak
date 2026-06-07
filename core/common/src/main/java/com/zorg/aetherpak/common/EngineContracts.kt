package com.zorg.aetherpak.common

/** Parameters for producing a backup archive. */
data class BackupRequest(
    val app: AppEntry,
    val codec: CodecType,
    val includePrivateData: Boolean,
    val includeObb: Boolean,
    val includeExternalData: Boolean,
    val compressionLevel: Int = 0,
    val outputDir: String
)

data class BackupOutcome(
    val archivePath: String,
    val manifest: BackupManifest,
    val totalBytes: Long,
    val durationMs: Long
)

/** Produces a `.ark` archive for an installed app via an [AccessProvider]. Lives in :core:backup. */
interface BackupEngine {
    suspend fun backup(request: BackupRequest, progress: ProgressCallback): OpResult<BackupOutcome>
}

/** Parameters for restoring a backup archive onto the current device. */
data class RestoreRequest(
    val archivePath: String,
    val installApk: Boolean = true,
    val restorePrivateData: Boolean = true,
    val restoreObb: Boolean = true,
    val restoreExternalData: Boolean = true
)

data class RestoreOutcome(
    val packageName: String,
    val newUid: Int,
    val filesRestored: Int,
    val durationMs: Long,
    val warnings: List<String>
)

/**
 * Installs an app's APK(s) and restores its payloads from a `.ark` archive, then repairs
 * ownership (chown) and SELinux contexts (restorecon). Lives in :core:restore.
 */
interface RestoreEngine {
    suspend fun readManifest(archivePath: String): OpResult<BackupManifest>
    suspend fun restore(request: RestoreRequest, progress: ProgressCallback): OpResult<RestoreOutcome>
}
