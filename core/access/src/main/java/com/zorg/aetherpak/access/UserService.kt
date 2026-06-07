package com.zorg.aetherpak.access

import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Backing implementation of [IUserService], instantiated by Shizuku inside a separate process that
 * holds ADB / shell (UID 2000) privileges. Everything here runs with those privileges — it can
 * `pm install`, read OBB and shared media, and run arbitrary shell commands, but it CANNOT read
 * another app's private /data/data/<pkg> (the kernel sandbox boundary). The provider enforces that
 * honesty; this class just executes.
 *
 * Shizuku requires either a no-arg constructor or a (Context) constructor. We expose both so the
 * service loader is satisfied regardless of how Shizuku instantiates it.
 */
class UserService : IUserService.Stub {

    @Suppress("unused")
    constructor() : super()

    @Suppress("unused")
    constructor(@Suppress("UNUSED_PARAMETER") context: android.content.Context) : super()

    override fun destroy() {
        // Politely terminate the dedicated service process when Shizuku unbinds.
        System.exit(0)
    }

    override fun exec(command: String?): String {
        val cmd = command ?: ""
        return try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(false)
                .start()

            val stdout = readStream(process.inputStream)
            val stderr = readStream(process.errorStream)
            val code = process.waitFor()

            frameResult(code, stdout, stderr)
        } catch (t: Throwable) {
            frameResult(-1, emptyList(), listOf(t.message ?: t.javaClass.simpleName))
        }
    }

    override fun openRead(path: String?): ParcelFileDescriptor? {
        val p = path ?: return null
        return try {
            ParcelFileDescriptor.open(File(p), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            null
        }
    }

    override fun openWrite(path: String?, append: Boolean): ParcelFileDescriptor? {
        val p = path ?: return null
        return try {
            val parent = File(p).parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            var flags = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            flags = if (append) {
                flags or ParcelFileDescriptor.MODE_APPEND
            } else {
                flags or ParcelFileDescriptor.MODE_TRUNCATE
            }
            ParcelFileDescriptor.open(File(p), flags)
        } catch (t: Throwable) {
            null
        }
    }

    private fun readStream(stream: java.io.InputStream): List<String> {
        val lines = ArrayList<String>()
        BufferedReader(InputStreamReader(stream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lines.add(line)
            }
        }
        return lines
    }

    companion object {
        const val MARKER_STDOUT = "STDOUT"
        const val MARKER_STDERR = "STDERR"
        const val PREFIX_EXIT = "EXIT="

        /** Flatten an execution into the framed String contract shared with the provider parser. */
        fun frameResult(code: Int, stdout: List<String>, stderr: List<String>): String {
            val sb = StringBuilder()
            sb.append(PREFIX_EXIT).append(code).append('\n')
            sb.append(MARKER_STDOUT).append('\n')
            for (l in stdout) sb.append(l).append('\n')
            sb.append(MARKER_STDERR).append('\n')
            for (l in stderr) sb.append(l).append('\n')
            return sb.toString()
        }
    }
}
