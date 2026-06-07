package com.zorg.aetherpak.common

/** Canonical on-device path templates and helpers shared by the backup and restore engines. */
object AetherPaths {

    /** Primary private data dir. On multi-user devices /data/user/0/<pkg> is the canonical alias. */
    fun privateData(pkg: String): String = "/data/data/$pkg"

    fun userPrivateData(pkg: String, userId: Int = 0): String = "/data/user/$userId/$pkg"

    fun deData(pkg: String, userId: Int = 0): String = "/data/user_de/$userId/$pkg"

    fun obbDir(pkg: String): String = "/sdcard/Android/obb/$pkg"

    fun externalDataDir(pkg: String): String = "/sdcard/Android/data/$pkg"

    /**
     * Subdirectories inside /data/data/<pkg> that must NOT be backed up: caches, code_cache,
     * and the no_backup marker dir. Restoring caches can corrupt app state and bloats archives.
     */
    val EXCLUDED_PRIVATE_SUBDIRS = setOf("cache", "code_cache", "no_backup", "lib")

    /** Files inside the .ark container. */
    const val ARCHIVE_DIR_APK = "apk"
    const val ARCHIVE_DIR_OBB = "obb"
    const val ARCHIVE_DIR_EXTDATA = "external_data"
    const val ARCHIVE_DIR_PRIVATE = "private_data"
    const val ARCHIVE_DIR_MEDIA = "media"

    const val ARCHIVE_EXTENSION = "ark"

    fun archiveFileName(pkg: String, versionCode: Long): String =
        "${pkg}_v${versionCode}.$ARCHIVE_EXTENSION"
}
