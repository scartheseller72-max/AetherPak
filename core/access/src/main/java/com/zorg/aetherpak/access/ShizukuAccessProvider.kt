package com.zorg.aetherpak.access

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.zorg.aetherpak.common.AccessCapabilities
import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.FileNode
import com.zorg.aetherpak.common.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * SHIZUKU backend. Privilege is brokered by the Shizuku service which runs our [UserService] with
 * ADB shell (UID 2000) rights. That UID can install packages and read OBB / shared media, but the
 * Android kernel sandbox forbids it from reading another app's private /data/data/<pkg>. We do NOT
 * pretend otherwise: any private-data path operation throws/returns an explicit unsupported result.
 *
 * @param context application context, used to derive the UserService component and process name.
 */
class ShizukuAccessProvider(private val context: Context) : AccessProvider {

    override val mode: AccessMode = AccessMode.SHIZUKU

    override val capabilities: AccessCapabilities = AccessCapabilities.shizuku()

    @Volatile
    private var userService: IUserService? = null

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, UserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("aetherpak_shizuku")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = if (binder != null && binder.pingBinder()) {
                IUserService.Stub.asInterface(binder)
            } else {
                null
            }
            pendingBind?.let { cont ->
                pendingBind = null
                cont(userService != null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
        }
    }

    @Volatile
    private var pendingBind: ((Boolean) -> Unit)? = null

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    override suspend fun requestAccess(): Boolean = withContext(Dispatchers.IO) {
        if (!safePing()) return@withContext false
        if (!ensurePermission()) return@withContext false
        ensureBound()
    }

    private fun safePing(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    private suspend fun ensurePermission(): Boolean {
        return try {
            if (Shizuku.isPreV11()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return true
            }
            requestPermissionSuspending()
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun requestPermissionSuspending(): Boolean =
        suspendCancellableCoroutine { cont ->
            val requestCode = REQUEST_CODE_PERMISSION
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(code: Int, grantResult: Int) {
                    if (code != requestCode) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) {
                        cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            try {
                Shizuku.requestPermission(requestCode)
            } catch (t: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
        }

    private suspend fun ensureBound(): Boolean {
        userService?.let { return true }
        return suspendCoroutine { cont ->
            pendingBind = { ok -> cont.resume(ok) }
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (t: Throwable) {
                pendingBind = null
                cont.resume(false)
            }
        }
    }

    private suspend fun service(): IUserService {
        userService?.let { return it }
        if (!ensureBound()) {
            throw IllegalStateException("Shizuku UserService is not bound; call requestAccess() first")
        }
        return userService
            ?: throw IllegalStateException("Shizuku UserService bound but interface is null")
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        runRemote(command)
    }

    override suspend fun execScript(script: String): ShellResult = withContext(Dispatchers.IO) {
        // Hand the whole script to a single remote `sh -c`; newlines are preserved verbatim.
        runRemote(script)
    }

    private fun runRemote(command: String): ShellResult {
        return try {
            val svc = userService
                ?: return ShellResult(-1, emptyList(), listOf("Shizuku UserService not bound"))
            val framed = svc.exec(command)
            parseFramed(framed)
        } catch (t: Throwable) {
            ShellResult.ofThrowable(t)
        }
    }

    /** Inverse of [UserService.frameResult]; robust to empty / malformed output. */
    private fun parseFramed(framed: String?): ShellResult {
        if (framed.isNullOrEmpty()) return ShellResult(0, emptyList(), emptyList())
        val lines = framed.split("\n")
        var code = -1
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        var section = 0 // 0=header, 1=stdout, 2=stderr
        for (line in lines) {
            when {
                line.startsWith(UserService.PREFIX_EXIT) -> {
                    code = line.removePrefix(UserService.PREFIX_EXIT).trim().toIntOrNull() ?: -1
                }
                line == UserService.MARKER_STDOUT -> section = 1
                line == UserService.MARKER_STDERR -> section = 2
                else -> when (section) {
                    1 -> stdout.add(line)
                    2 -> stderr.add(line)
                    else -> { /* stray header noise; ignore */ }
                }
            }
        }
        // Trim a single trailing empty element introduced by the framing's final '\n'.
        if (stderr.isNotEmpty() && stderr.last().isEmpty()) stderr.removeAt(stderr.size - 1)
        if (stdout.isNotEmpty() && stdout.last().isEmpty() && section == 1) {
            stdout.removeAt(stdout.size - 1)
        }
        return ShellResult(code, stdout, stderr)
    }

    override suspend fun stat(path: String): FileNode? = withContext(Dispatchers.IO) {
        guardPrivateData(path)
        val q = StatParsing.shellQuote(path)
        val res = runRemote("stat -c '${StatParsing.STAT_FORMAT}' $q")
        if (!res.isSuccess) return@withContext null
        val line = res.stdout.firstOrNull { it.isNotBlank() } ?: return@withContext null
        val node = StatParsing.parseStatLine(line, knownPath = path) ?: return@withContext null
        val ctx = readSeContext(path)
        if (ctx != null) node.copy(seContext = ctx) else node
    }

    private fun readSeContext(path: String): String? {
        val res = runRemote("ls -Z -d ${StatParsing.shellQuote(path)}")
        if (!res.isSuccess) return null
        val line = res.stdout.firstOrNull { it.isNotBlank() } ?: return null
        return StatParsing.parseSeContext(line)
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        // exists() is allowed even for private paths (the shell can stat metadata of the dir entry
        // in some cases) but reads are not; keep it cheap and honest via a test command.
        if (isPrivateData(path)) return@withContext false
        runRemote("[ -e ${StatParsing.shellQuote(path)} ] && echo Y || echo N")
            .stdout.firstOrNull { it.isNotBlank() }?.trim() == "Y"
    }

    override suspend fun walk(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        guardPrivateData(path)
        val res = runRemote(StatParsing.buildWalkCommand(path))
        if (res.stdout.isEmpty()) return@withContext emptyList()
        res.stdout.mapNotNull { StatParsing.parseWalkLine(it) }
    }

    override suspend fun readFileStream(path: String): InputStream = withContext(Dispatchers.IO) {
        guardPrivateData(path)
        val svc = service()
        val pfd: ParcelFileDescriptor = svc.openRead(path)
            ?: throw java.io.FileNotFoundException("Shizuku UserService could not open: $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    override suspend fun writeFileStream(path: String, append: Boolean): OutputStream =
        withContext(Dispatchers.IO) {
            guardPrivateData(path)
            val svc = service()
            val pfd: ParcelFileDescriptor = svc.openWrite(path, append)
                ?: throw java.io.FileNotFoundException("Shizuku UserService could not open for write: $path")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        }

    override suspend fun mkdirs(path: String, mode: Int): ShellResult = withContext(Dispatchers.IO) {
        if (isPrivateData(path)) return@withContext unsupportedPrivate("mkdirs")
        val q = StatParsing.shellQuote(path)
        runRemote("mkdir -p $q && chmod ${Integer.toOctalString(mode)} $q")
    }

    override suspend fun chown(path: String, uid: Int, gid: Int, recursive: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            // Capability matrix already advertises canChangeOwnership=false. Be explicit.
            ShellResult(
                code = 1,
                stdout = emptyList(),
                stderr = listOf("chown unsupported in SHIZUKU mode (shell UID cannot change ownership of other apps' files; root required)")
            )
        }

    override suspend fun chmod(path: String, mode: Int, recursive: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            if (isPrivateData(path)) return@withContext unsupportedPrivate("chmod")
            val flag = if (recursive) "-R " else ""
            runRemote("chmod $flag${Integer.toOctalString(mode)} ${StatParsing.shellQuote(path)}")
        }

    override suspend fun restoreSeContext(path: String, recursive: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            ShellResult(
                code = 1,
                stdout = emptyList(),
                stderr = listOf("restorecon unsupported in SHIZUKU mode (SELinux relabel requires root)")
            )
        }

    override suspend fun setSeContext(path: String, context: String, recursive: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            ShellResult(
                code = 1,
                stdout = emptyList(),
                stderr = listOf("chcon unsupported in SHIZUKU mode (SELinux relabel requires root)")
            )
        }

    private fun isPrivateData(path: String): Boolean {
        val p = path.trimEnd('/')
        return p.startsWith("/data/data/") ||
            p.startsWith("/data/user/") ||
            p.startsWith("/data/user_de/")
    }

    /** Throw a clear, honest error when a private-data path is requested under Shizuku. */
    private fun guardPrivateData(path: String) {
        if (isPrivateData(path)) {
            throw UnsupportedOperationException("Shizuku cannot access private app data; root required")
        }
    }

    private fun unsupportedPrivate(op: String): ShellResult = ShellResult(
        code = 1,
        stdout = emptyList(),
        stderr = listOf("$op on private app data unsupported in SHIZUKU mode; root required")
    )

    companion object {
        private const val REQUEST_CODE_PERMISSION = 0xA37E // "AETHER"-ish marker
    }
}
