package com.zorg.aetherpak.common

/**
 * Result of an elevated shell command. Both ROOT and SHIZUKU providers normalize their
 * output into this shape so the backup/restore engines never branch on the backend.
 */
data class ShellResult(
    val code: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    val isSuccess: Boolean get() = code == 0
    val out: String get() = stdout.joinToString("\n")
    val err: String get() = stderr.joinToString("\n")

    companion object {
        fun ofThrowable(t: Throwable) = ShellResult(
            code = -1,
            stdout = emptyList(),
            stderr = listOf(t.message ?: t.javaClass.simpleName)
        )
    }
}

/**
 * A single filesystem node as reported by an elevated `ls`/`stat` style listing. Captures
 * everything the restore stage must reproduce: permission bits, ownership, symlink targets,
 * and the SELinux security context.
 */
data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** Octal permission bits, e.g. 0b110_000_000 == 0o600. Stored as decimal Int of the octal value. */
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val seContext: String? = null,
    val linkTarget: String? = null
)
