package com.zorg.aetherpak.common

/**
 * The single abstraction the rest of AetherPak codes against. Concrete implementations live
 * in :core:access — RootAccessProvider (libsu) and ShizukuAccessProvider (Shizuku UserService).
 *
 * Every elevated operation (reading private data, installing APKs, chown, restorecon) flows
 * through this interface so the backup and restore engines remain backend-agnostic. Callers
 * MUST consult [capabilities] before invoking an operation the backend may not support
 * (e.g. [readFileStream] of /data/data/<pkg> under SHIZUKU).
 */
interface AccessProvider {

    val mode: AccessMode

    val capabilities: AccessCapabilities

    /** True if the backend binary/service is present and reachable (su exists, Shizuku running). */
    suspend fun isAvailable(): Boolean

    /**
     * Request the actual privilege grant: triggers the Magisk/KernelSU su prompt, or the
     * Shizuku permission dialog. Returns true once elevation is held.
     */
    suspend fun requestAccess(): Boolean

    /** Run a single command line through the elevated shell. */
    suspend fun exec(command: String): ShellResult

    /** Run a multi-line script (heredoc / sh -c) through the elevated shell. */
    suspend fun execScript(script: String): ShellResult

    /** Stat a path; returns null when it does not exist or is unreadable by this backend. */
    suspend fun stat(path: String): FileNode?

    /** True when the path exists and is reachable by this backend. */
    suspend fun exists(path: String): Boolean

    /** Recursively enumerate a directory, preserving mode/uid/gid/symlink/SELinux metadata. */
    suspend fun walk(path: String): List<FileNode>

    /**
     * Open an elevated read stream for [path]. The stream is owned by the caller and must be
     * closed. Implementations route through libsu's FileSystemManager (ROOT) or the Shizuku
     * UserService file helper (SHIZUKU, OBB/media paths only).
     */
    suspend fun readFileStream(path: String): java.io.InputStream

    /**
     * Open an elevated write stream for [path], creating parent directories as needed.
     */
    suspend fun writeFileStream(path: String, append: Boolean = false): java.io.OutputStream

    /** Create a directory (and parents) with the given octal [mode]. */
    suspend fun mkdirs(path: String, mode: Int = 0b111_101_101 /* 0o755 */): ShellResult

    /** chown -R uid:gid path (no-op failure when [AccessCapabilities.canChangeOwnership] is false). */
    suspend fun chown(path: String, uid: Int, gid: Int, recursive: Boolean = true): ShellResult

    /** chmod octal mode. */
    suspend fun chmod(path: String, mode: Int, recursive: Boolean = false): ShellResult

    /** restorecon -R path (SELinux). No-op failure when unsupported. */
    suspend fun restoreSeContext(path: String, recursive: Boolean = true): ShellResult

    /** Explicitly set a single SELinux context via chcon. */
    suspend fun setSeContext(path: String, context: String, recursive: Boolean = false): ShellResult
}
