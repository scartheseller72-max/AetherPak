package com.zorg.aetherpak.access

import com.zorg.aetherpak.common.FileNode

/**
 * Shared, backend-agnostic parsing helpers. Both providers stat/walk by issuing the same
 * `stat`/`ls -Z`/`find` invocations against their elevated shell, so the line-parsing logic
 * lives here once.
 *
 * The canonical stat format used everywhere is:
 *   stat -c '%f|%a|%u|%g|%s|%N' <path>
 * where
 *   %f = raw mode in hex (used only to derive type bits: dir / symlink / regular),
 *   %a = access rights in octal (e.g. 755) -> parsed with radix 8 into FileNode.mode,
 *   %u = owner uid, %g = group gid, %s = size in bytes,
 *   %N = quoted file name, with `-> 'target'` appended for symlinks.
 */
internal object StatParsing {

    /** The single stat format string reused by stat() and walk(). */
    const val STAT_FORMAT = "%f|%a|%u|%g|%s|%N"

    /** S_IFMT mask and type constants from <sys/stat.h>, matched against the hex %f field. */
    private const val S_IFMT = 0xF000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000

    /**
     * Parse one line produced by `stat -c '$STAT_FORMAT' <path>`. The [knownPath] is the path we
     * asked stat about; %N only yields the basename (possibly a symlink arrow), so we prefer
     * [knownPath] for FileNode.path when supplied.
     *
     * Returns null when the line is blank or malformed (robust to empty output).
     */
    fun parseStatLine(line: String, knownPath: String? = null): FileNode? {
        val raw = line.trim()
        if (raw.isEmpty()) return null

        // Split into at most 6 segments; %N itself may contain '|' inside a file name, so the
        // name segment is everything after the 5th delimiter.
        val parts = raw.split("|", limit = 6)
        if (parts.size < 6) return null

        val rawModeHex = parts[0].trim()
        val octalPerm = parts[1].trim()
        val uidStr = parts[2].trim()
        val gidStr = parts[3].trim()
        val sizeStr = parts[4].trim()
        val nameField = parts[5].trim()

        val modeHex = rawModeHex.toIntOrNull(16) ?: return null
        val mode = octalPerm.toIntOrNull(8) ?: 0
        val uid = uidStr.toIntOrNull() ?: -1
        val gid = gidStr.toIntOrNull() ?: -1
        val size = sizeStr.toLongOrNull() ?: 0L

        val isDir = (modeHex and S_IFMT) == S_IFDIR
        val isSymlink = (modeHex and S_IFMT) == S_IFLNK

        // %N renders as: 'name'  or for links  'name' -> 'target'
        val (displayName, linkTarget) = parseNameField(nameField)

        val path = knownPath ?: displayName
        val name = displayName.substringAfterLast('/').ifEmpty { path.substringAfterLast('/') }

        return FileNode(
            path = path,
            name = name,
            isDirectory = isDir,
            isSymlink = isSymlink,
            size = size,
            mode = mode,
            uid = uid,
            gid = gid,
            seContext = null,
            linkTarget = if (isSymlink) linkTarget else null
        )
    }

    /** Unwrap the quoted `%N` field into (name, optional symlink target). */
    private fun parseNameField(field: String): Pair<String, String?> {
        val arrow = " -> "
        return if (field.contains(arrow)) {
            val left = field.substringBefore(arrow)
            val right = field.substringAfter(arrow)
            unquote(left) to unquote(right)
        } else {
            unquote(field) to null
        }
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return when {
            t.length >= 2 && t.startsWith('\'') && t.endsWith('\'') -> t.substring(1, t.length - 1)
            t.length >= 2 && t.startsWith('"') && t.endsWith('"') -> t.substring(1, t.length - 1)
            else -> t
        }
    }

    /**
     * Parse one `ls -Z -d <path>` line into the SELinux context. Layout (toybox/SELinux):
     *   u:object_r:app_data_file:s0 -rw------- uid gid ... name
     * The context is the first whitespace token of the form a:b:c:d.
     * Returns null when no SELinux label is present.
     */
    fun parseSeContext(line: String): String? {
        val raw = line.trim()
        if (raw.isEmpty()) return null
        for (token in raw.split(Regex("\\s+"))) {
            // A valid context has at least user:role:type:level (3+ colons).
            if (token.count { it == ':' } >= 3) return token
        }
        return null
    }

    /**
     * Build the find-based recursive walk command. Each entry is emitted on its own line using
     * the canonical stat format with an absolute %n path, so parseWalkLine can reconstruct the
     * full FileNode list with real paths.
     *
     * We use `-printf` when available is NOT assumed (toybox find lacks it on many devices), so
     * we fall back to `find ... -exec stat`. The %N from stat under -exec yields the full path
     * because we pass the absolute path to stat.
     */
    fun buildWalkCommand(path: String): String {
        val q = shellQuote(path)
        // find prints absolute paths; for each, stat with our format. The leading "P|" tag plus
        // the absolute path lets us recover FileNode.path independent of %N quoting quirks.
        return "find $q -exec stat -c 'P|%f|%a|%u|%g|%s|%n' {} \\;"
    }

    /**
     * Parse a line produced by buildWalkCommand: "P|<hex>|<octal>|<uid>|<gid>|<size>|<abs-path>".
     * Robust to blank lines.
     */
    fun parseWalkLine(line: String): FileNode? {
        val raw = line.trim()
        if (raw.isEmpty() || !raw.startsWith("P|")) return null
        val body = raw.removePrefix("P|")
        val parts = body.split("|", limit = 6)
        if (parts.size < 6) return null

        val modeHex = parts[0].trim().toIntOrNull(16) ?: return null
        val mode = parts[1].trim().toIntOrNull(8) ?: 0
        val uid = parts[2].trim().toIntOrNull() ?: -1
        val gid = parts[3].trim().toIntOrNull() ?: -1
        val size = parts[4].trim().toLongOrNull() ?: 0L
        val absPath = parts[5].trim()
        if (absPath.isEmpty()) return null

        val isDir = (modeHex and S_IFMT) == S_IFDIR
        val isSymlink = (modeHex and S_IFMT) == S_IFLNK

        return FileNode(
            path = absPath,
            name = absPath.substringAfterLast('/').ifEmpty { absPath },
            isDirectory = isDir,
            isSymlink = isSymlink,
            size = size,
            mode = mode,
            uid = uid,
            gid = gid,
            seContext = null,
            linkTarget = null
        )
    }

    /** Single-quote a path for safe shell interpolation, escaping embedded single quotes. */
    fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
