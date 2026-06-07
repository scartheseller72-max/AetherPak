package com.zorg.aetherpak.access

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import com.zorg.aetherpak.common.AccessCapabilities
import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.FileNode
import com.zorg.aetherpak.common.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * ROOT backend built on topjohnwu/libsu. A real superuser shell is acquired in mount-master
 * namespace so that bind mounts / decrypted storage are visible. File I/O routes through libsu's
 * SuFile / SuFileInputStream / SuFileOutputStream which proxy real file descriptors out of the
 * root shell — this is the only mode that can read and write another package's private
 * /data/data/<pkg> tree.
 */
class RootAccessProvider : AccessProvider {

    override val mode: AccessMode = AccessMode.ROOT

    override val capabilities: AccessCapabilities = AccessCapabilities.root()

    init {
        // Ensure every libsu shell is a mount-master root shell. Set once, before first getShell().
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(30)
        )
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell().isRoot
        } catch (t: Throwable) {
            false
        }
    }

    override suspend fun requestAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            // getShell() blocks until the su request is resolved (granted or denied).
            val shell = Shell.getShell()
            shell.isRoot
        } catch (t: Throwable) {
            false
        }
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        runCmd(command)
    }

    override suspend fun execScript(script: String): ShellResult = withContext(Dispatchers.IO) {
        // libsu accepts multiple commands; feed the script's lines so heredocs and multi-line
        // constructs are evaluated by the same root sh instance.
        val lines = script.split("\n").toTypedArray()
        runCmd(*lines)
    }

    private fun runCmd(vararg commands: String): ShellResult {
        return try {
            val res = Shell.cmd(*commands).exec()
            ShellResult(
                code = res.code,
                stdout = res.out ?: emptyList(),
                stderr = res.err ?: emptyList()
            )
        } catch (t: Throwable) {
            ShellResult.ofThrowable(t)
        }
    }

    override suspend fun stat(path: String): FileNode? = withContext(Dispatchers.IO) {
        val q = StatParsing.shellQuote(path)
        val statRes = runCmd("stat -c '${StatParsing.STAT_FORMAT}' $q")
        if (!statRes.isSuccess) return@withContext null
        val line = statRes.stdout.firstOrNull { it.isNotBlank() } ?: return@withContext null
        val node = StatParsing.parseStatLine(line, knownPath = path) ?: return@withContext null

        val ctx = readSeContext(path)
        if (ctx != null) node.copy(seContext = ctx) else node
    }

    private fun readSeContext(path: String): String? {
        val q = StatParsing.shellQuote(path)
        val res = runCmd("ls -Z -d $q")
        if (!res.isSuccess) return null
        val line = res.stdout.firstOrNull { it.isNotBlank() } ?: return null
        return StatParsing.parseSeContext(line)
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val q = StatParsing.shellQuote(path)
        runCmd("[ -e $q ] && echo Y || echo N").stdout
            .firstOrNull { it.isNotBlank() }?.trim() == "Y"
    }

    override suspend fun walk(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val res = runCmd(StatParsing.buildWalkCommand(path))
        if (res.stdout.isEmpty()) return@withContext emptyList()
        val nodes = res.stdout.mapNotNull { StatParsing.parseWalkLine(it) }
        // Enrich with SELinux contexts in a single batched `ls -Z -d` over all discovered paths.
        attachSeContexts(nodes)
    }

    private fun attachSeContexts(nodes: List<FileNode>): List<FileNode> {
        if (nodes.isEmpty()) return nodes
        val byPath = HashMap<String, String>(nodes.size * 2)
        // Chunk to avoid exceeding ARG_MAX on very large trees.
        nodes.chunked(200).forEach { chunk ->
            val args = chunk.joinToString(" ") { StatParsing.shellQuote(it.path) }
            val res = runCmd("ls -Z -d $args")
            res.stdout.forEach { line ->
                val ctx = StatParsing.parseSeContext(line) ?: return@forEach
                // Match the context to a node path by suffix; ls -Z appends the path as the last token.
                val token = line.trim().split(Regex("\\s+")).lastOrNull() ?: return@forEach
                byPath[token] = ctx
            }
        }
        return nodes.map { n -> byPath[n.path]?.let { n.copy(seContext = it) } ?: n }
    }

    override suspend fun readFileStream(path: String): InputStream = withContext(Dispatchers.IO) {
        // SuFileInputStream proxies a real fd out of the root shell, so private /data/data paths
        // are readable here in a way the app process never could be on its own.
        SuFileInputStream.open(SuFile(path))
    }

    override suspend fun writeFileStream(path: String, append: Boolean): OutputStream =
        withContext(Dispatchers.IO) {
            val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotEmpty()) {
                runCmd("mkdir -p ${StatParsing.shellQuote(parent)}")
            }
            SuFileOutputStream.open(SuFile(path), append)
        }

    override suspend fun mkdirs(path: String, mode: Int): ShellResult = withContext(Dispatchers.IO) {
        val q = StatParsing.shellQuote(path)
        val octal = toOctalString(mode)
        runCmd("mkdir -p $q", "chmod $octal $q")
    }

    override suspend fun chown(path: String, uid: Int, gid: Int, recursive: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            val flag = if (recursive) "-R " else ""
            runCmd("chown $flag$uid:$gid ${StatParsing.shellQuote(path)}")
        }

    override suspend fun chmod(path: String, mode: Int, recursive: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            val flag = if (recursive) "-R " else ""
            runCmd("chmod $flag${toOctalString(mode)} ${StatParsing.shellQuote(path)}")
        }

    override suspend fun restoreSeContext(path: String, recursive: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            val flag = if (recursive) "-R " else ""
            runCmd("restorecon $flag${StatParsing.shellQuote(path)}")
        }

    override suspend fun setSeContext(path: String, context: String, recursive: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            val flag = if (recursive) "-R " else ""
            runCmd("chcon $flag${StatParsing.shellQuote(context)} ${StatParsing.shellQuote(path)}")
        }

    /** FileNode.mode is the decimal value of the octal permission; render it back to an octal string. */
    private fun toOctalString(mode: Int): String = Integer.toOctalString(mode)
}
