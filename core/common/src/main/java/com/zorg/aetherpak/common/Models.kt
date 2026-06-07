package com.zorg.aetherpak.common

import kotlinx.serialization.Serializable

/** Compression codec selectable for an archive. All produce a `.ark` container. */
enum class CodecType(val id: String, val displayName: String) {
    ZSTD("zstd", "Zstandard (fast)"),
    ZIP("zip", "ZIP (compatible)"),
    SEVEN_ZIP("7z", "7z / LZMA2 (smallest)");

    companion object {
        fun fromId(id: String): CodecType = entries.firstOrNull { it.id == id } ?: ZSTD
    }
}

/** What kind of payload a backup component / manifest entry represents. */
@Serializable
enum class BackupComponentType {
    BASE_APK,
    SPLIT_APK,
    OBB,
    EXTERNAL_DATA,   // /sdcard/Android/data/<pkg>
    PRIVATE_DATA,    // /data/data/<pkg> — ROOT only
    MEDIA            // arbitrary shared-media files the user added to the set
}

/** An installed application surfaced in the picker. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val dataDir: String,
    val hasObb: Boolean,
    val obbDir: String,
    val externalDataDir: String,
    val uid: Int,
    val totalApkBytes: Long,
    val iconBase64: String? = null
)

/** Summary of one component within a produced backup, surfaced in the manifest and UI. */
@Serializable
data class BackupComponent(
    val type: BackupComponentType,
    val included: Boolean,
    val fileCount: Int,
    val totalBytes: Long,
    val rootPathOnDevice: String,
    val skippedReason: String? = null
)

/**
 * One file/dir/symlink recorded in the archive. The restore stage reproduces [mode], remaps
 * ownership using [uid]/[gid] against the manifest's [BackupManifest.sourceUid], and reapplies
 * [seContext]. Directories and symlinks carry no archive payload.
 */
@Serializable
data class ManifestEntry(
    val archivePath: String,
    val devicePath: String,
    val type: BackupComponentType,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val linkTarget: String? = null,
    val size: Long,
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val seContext: String? = null
)

/**
 * The archive's table of contents and provenance. Serialized to `manifest.json` at the root of
 * every `.ark` container. [sourceUid] is the app's UID on the origin device; restore computes
 * `delta = newAppUid - sourceUid` and shifts each entry's [ManifestEntry.uid]/[ManifestEntry.gid]
 * that falls inside the app range — the standard TWRP/Neo-style ownership remap.
 */
@Serializable
data class BackupManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val createdAtEpochMs: Long,
    val createdByMode: String,
    val codec: String,
    val sourceUid: Int,
    val sourceGid: Int,
    val components: List<BackupComponent>,
    val entries: List<ManifestEntry>,
    val isPartial: Boolean,
    val notes: String? = null
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val MANIFEST_FILENAME = "manifest.json"
    }
}
