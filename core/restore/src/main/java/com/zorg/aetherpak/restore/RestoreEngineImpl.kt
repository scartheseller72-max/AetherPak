package com.zorg.aetherpak.restore

import android.content.Context
import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.AetherPaths
import com.zorg.aetherpak.common.ArchiveEntryHeader
import com.zorg.aetherpak.common.ArchiveReader
import com.zorg.aetherpak.common.BackupComponentType
import com.zorg.aetherpak.common.BackupManifest
import com.zorg.aetherpak.common.CodecType
import com.zorg.aetherpak.common.ErrorCode
import com.zorg.aetherpak.common.ManifestEntry
import com.zorg.aetherpak.common.NativeArchive
import com.zorg.aetherpak.common.OpResult
import com.zorg.aetherpak.common.OperationProgress
import com.zorg.aetherpak.common.ProgressCallback
import com.zorg.aetherpak.common.RestoreEngine
import com.zorg.aetherpak.common.RestoreOutcome
import com.zorg.aetherpak.common.RestoreRequest
import com.zorg.aetherpak.compress.AetherArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Production [RestoreEngine].
 *
 * Restore is the mirror of backup, run in reverse and with a critical extra step:
 *   1. PREPARING/VERIFYING  — read + validate the manifest, honor backend capabilities.
 *   2. INSTALLING           — extract apk/ entries, `pm install-create/-write/-commit`.
 *   3. RESTORING_FILES      — stream every non-apk, non-manifest entry to its device path.
 *   4. FIXING_PERMISSIONS   — resolve the NEW uid, delta-remap chown, restorecon. (see [PermissionFixer])
 *
 * Step 4 is non-optional for private data: skipping the uid remap leaves files owned by the
 * origin device's uid and the app crashes on first launch (kernel/SELinux denial). When the
 * backend cannot write private data (e.g. SHIZUKU), private data is honestly SKIPPED with a
 * warning rather than silently "succeeding".
 */
class RestoreEngineImpl(
    private val context: Context,
    private val access: AccessProvider,
    private val archive: NativeArchive = AetherArchive
) : RestoreEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val fixer = PermissionFixer(access)

    private companion object {
        const val CHUNK = 64 * 1024
        // Candidate decode order when the codec isn't yet known (readManifest before parse).
        val CODEC_PROBE_ORDER = listOf(CodecType.ZSTD, CodecType.ZIP, CodecType.SEVEN_ZIP)
    }

    // ---------------------------------------------------------------------------------------------
    // Manifest
    // ---------------------------------------------------------------------------------------------

    override suspend fun readManifest(archivePath: String): OpResult<BackupManifest> =
        withContext(Dispatchers.IO) {
            if (!File(archivePath).exists()) {
                return@withContext OpResult.fail(ErrorCode.IO_ERROR, "Archive not found: $archivePath")
            }
            // The codec is recorded inside the manifest, but we must pick a codec to open the
            // reader. Probe codecs until one yields a parseable manifest entry.
            var lastError: Throwable? = null
            for (codec in CODEC_PROBE_ORDER) {
                try {
                    val parsed = scanForManifest(archivePath, codec)
                    if (parsed != null) return@withContext OpResult.success(parsed)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            OpResult.fail(
                ErrorCode.MANIFEST_INVALID,
                "Could not locate or parse ${BackupManifest.MANIFEST_FILENAME} in archive",
                lastError
            )
        }

    /** Open with [codec], scan entries to the manifest, parse it. Returns null if not found. */
    private fun scanForManifest(archivePath: String, codec: CodecType): BackupManifest? {
        archive.openReader(archivePath, codec).use { reader ->
            while (true) {
                val header = reader.nextEntry() ?: break
                if (entryLeafName(header.path) == BackupManifest.MANIFEST_FILENAME) {
                    val bytes = drainEntry(reader)
                    return json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
                }
                // Not the manifest: drain its payload so the stream advances correctly.
                drainEntryDiscard(reader)
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Restore
    // ---------------------------------------------------------------------------------------------

    override suspend fun restore(
        request: RestoreRequest,
        progress: ProgressCallback
    ): OpResult<RestoreOutcome> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val warnings = mutableListOf<String>()

        // ---- PHASE PREPARING / VERIFYING ------------------------------------------------------
        progress(OperationProgress(OperationProgress.Phase.PREPARING, message = "Reading manifest"))
        val manifest = when (val m = readManifest(request.archivePath)) {
            is OpResult.Success -> m.value
            is OpResult.Failure -> return@withContext m
        }
        val pkg = manifest.packageName
        val codec = CodecType.fromId(manifest.codec)

        progress(OperationProgress(OperationProgress.Phase.VERIFYING, message = "Validating capabilities for $pkg"))

        val wantsPrivate = request.restorePrivateData &&
            manifest.entries.any { it.type == BackupComponentType.PRIVATE_DATA }
        val canPrivate = wantsPrivate && access.capabilities.canWritePrivateData
        if (wantsPrivate && !access.capabilities.canWritePrivateData) {
            warnings += "Private app data present in backup but ${access.mode} mode cannot write " +
                "/data/data/$pkg — private data SKIPPED. The app will start with no saved state."
        }

        // Decide which archive prefixes we will actually restore for this run.
        val restorePrefixes = buildSet {
            if (canPrivate) add(AetherPaths.ARCHIVE_DIR_PRIVATE)
            if (request.restoreObb) add(AetherPaths.ARCHIVE_DIR_OBB)
            if (request.restoreExternalData) add(AetherPaths.ARCHIVE_DIR_EXTDATA)
            add(AetherPaths.ARCHIVE_DIR_MEDIA)
        }

        // Index manifest entries by archivePath for authoritative metadata (uid/gid/mode/seContext).
        val entryByArchivePath = manifest.entries.associateBy { normalize(it.archivePath) }

        try {
            // ---- PHASE INSTALLING --------------------------------------------------------------
            if (request.installApk) {
                progress(OperationProgress(OperationProgress.Phase.INSTALLING, message = "Installing $pkg"))
                when (val r = installApks(request.archivePath, codec, pkg)) {
                    is OpResult.Failure -> return@withContext r
                    is OpResult.Success -> { /* installed */ }
                }
                coroutineContext.ensureActive()
            }

            // ---- PHASE RESTORING_FILES ---------------------------------------------------------
            progress(OperationProgress(OperationProgress.Phase.RESTORING_FILES, message = "Restoring files"))
            val restoredPrivateEntries = mutableListOf<ManifestEntry>()
            var filesRestored = 0
            val totalFiles = manifest.entries.count { !it.isDirectory && !it.isSymlink }

            archive.openReader(request.archivePath, codec).use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val header = reader.nextEntry() ?: break
                    val archivePath = normalize(header.path)
                    val prefix = topPrefix(archivePath)

                    // Skip apk entries (handled in INSTALLING) and the manifest itself.
                    if (prefix == AetherPaths.ARCHIVE_DIR_APK ||
                        entryLeafName(archivePath) == BackupManifest.MANIFEST_FILENAME
                    ) {
                        drainEntryDiscard(reader)
                        continue
                    }
                    // Skip components excluded by this request / capability set.
                    if (prefix !in restorePrefixes) {
                        drainEntryDiscard(reader)
                        continue
                    }

                    val meta = entryByArchivePath[archivePath]
                    val devicePath = meta?.devicePath ?: reconstructDevicePath(archivePath, manifest, pkg)
                    if (devicePath == null) {
                        // Unknown mapping; drain and move on rather than crash.
                        drainEntryDiscard(reader)
                        continue
                    }
                    // A crafted manifest can point devicePath anywhere; refuse to write outside
                    // the package's own roots for this prefix.
                    if (!isDevicePathAllowed(devicePath, prefix, pkg)) {
                        warnings += "Skipped entry with out-of-bounds target path: $devicePath"
                        drainEntryDiscard(reader)
                        continue
                    }

                    when {
                        header.isDirectory -> {
                            access.mkdirs(devicePath, header.mode.takeIf { it != 0 } ?: 0b111_101_101)
                            drainEntryDiscard(reader)
                        }
                        header.isSymlink -> {
                            val target = header.linkTarget ?: meta?.linkTarget
                            if (target != null) recreateSymlink(target, devicePath)
                            drainEntryDiscard(reader)
                        }
                        else -> {
                            progress(
                                OperationProgress(
                                    phase = OperationProgress.Phase.RESTORING_FILES,
                                    currentFile = devicePath,
                                    processedFiles = filesRestored,
                                    totalFiles = totalFiles
                                )
                            )
                            streamEntryToDevice(reader, devicePath)
                            val mode = (meta?.mode ?: header.mode)
                            if (mode != 0) access.chmod(devicePath, mode, recursive = false)
                            filesRestored++
                            if (prefix == AetherPaths.ARCHIVE_DIR_PRIVATE && meta != null) {
                                restoredPrivateEntries += meta
                            }
                        }
                    }
                }
            }

            // ---- PHASE FIXING_PERMISSIONS (CRITICAL) -------------------------------------------
            progress(OperationProgress(OperationProgress.Phase.FIXING_PERMISSIONS, message = "Resolving new UID"))
            var newUid = -1
            if (canPrivate) {
                newUid = UidResolver.resolveAppUid(access, pkg)
                if (newUid <= 0) {
                    warnings += "Could not resolve the new UID for $pkg; ownership NOT remapped. " +
                        "The app will likely crash — re-run restore once the package is installed."
                } else {
                    // Include directory + symlink private entries too, so the whole tree is owned right.
                    val privateEntries = manifest.entries.filter { it.type == BackupComponentType.PRIVATE_DATA }
                    val report = fixer.fixPrivateData(pkg, privateEntries, newUid, manifest)
                    warnings += report.warnings
                    // Final authoritative sweep mandated by the spec.
                    access.restoreSeContext(AetherPaths.privateData(pkg), recursive = true)
                }
            } else {
                // Still try to learn the uid for the outcome report (best-effort), but no chown needed.
                newUid = UidResolver.resolveAppUid(access, pkg)
            }

            // OBB / external_data live on the FUSE-emulated sdcard: ownership is synthesized by the
            // emulation layer, so chown is a no-op there. Files were placed above; nothing to remap.

            coroutineContext.ensureActive()
            val durationMs = System.currentTimeMillis() - startedAt
            progress(OperationProgress(OperationProgress.Phase.DONE, processedFiles = filesRestored))
            OpResult.success(
                RestoreOutcome(
                    packageName = pkg,
                    newUid = newUid,
                    filesRestored = filesRestored,
                    durationMs = durationMs,
                    warnings = warnings
                )
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            progress(OperationProgress(OperationProgress.Phase.FAILED, message = "Cancelled"))
            OpResult.fail(ErrorCode.CANCELLED, "Restore cancelled", ce)
        } catch (t: Throwable) {
            progress(OperationProgress(OperationProgress.Phase.FAILED, message = t.message))
            OpResult.fail(ErrorCode.IO_ERROR, t.message ?: "Restore failed", t)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // APK install
    // ---------------------------------------------------------------------------------------------

    /**
     * Extracts apk/ entries to a private temp dir, then installs via a pm session:
     *   pm install-create -r   -> "Success: created install session [<id>]"
     *   pm install-write -S <size> <id> <name> <path>   (per apk)
     *   pm install-commit <id>
     */
    private suspend fun installApks(
        archivePath: String,
        codec: CodecType,
        pkg: String
    ): OpResult<Unit> {
        val tmpDir = File(context.cacheDir, "restore_apk_${System.nanoTime()}").apply { mkdirs() }
        val apks = mutableListOf<File>()
        try {
            archive.openReader(archivePath, codec).use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val header = reader.nextEntry() ?: break
                    val ap = normalize(header.path)
                    if (topPrefix(ap) != AetherPaths.ARCHIVE_DIR_APK || header.isDirectory || header.isSymlink) {
                        drainEntryDiscard(reader)
                        continue
                    }
                    // Sanitize the on-disk name: the archive entry name is attacker-influenced and
                    // is later interpolated into a shell `pm install-write` command.
                    val safeLeaf = entryLeafName(ap).replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .ifEmpty { "base_${apks.size}.apk" }
                    val out = File(tmpDir, safeLeaf)
                    out.outputStream().use { os ->
                        val buf = ByteArray(CHUNK)
                        var n = reader.read(buf)
                        while (n >= 0) {
                            if (n > 0) os.write(buf, 0, n)
                            n = reader.read(buf)
                        }
                    }
                    apks += out
                }
            }
            if (apks.isEmpty()) {
                return OpResult.fail(ErrorCode.INSTALL_FAILED, "No APK entries found in archive for $pkg")
            }

            // Make the staged APKs readable by the installer (the elevated shell reads them by path).
            for (f in apks) access.chmod(f.absolutePath, 0b110_100_100 /* 0o644 */, recursive = false)

            val create = access.exec("pm install-create -r")
            if (!create.isSuccess) {
                return OpResult.fail(ErrorCode.INSTALL_FAILED, "install-create failed: ${create.err}")
            }
            val sessionId = parseSessionId(create.out)
                ?: return OpResult.fail(ErrorCode.INSTALL_FAILED, "Could not parse install session id from: ${create.out}")

            for (f in apks) {
                coroutineContext.ensureActive()
                val size = f.length()
                val safeName = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val write = access.exec("pm install-write -S $size $sessionId $safeName ${shellQuote(f.absolutePath)}")
                if (!write.isSuccess) {
                    access.exec("pm install-abandon $sessionId")
                    return OpResult.fail(ErrorCode.INSTALL_FAILED, "install-write failed for ${f.name}: ${write.err}")
                }
            }

            val commit = access.exec("pm install-commit $sessionId")
            if (!commit.isSuccess || commit.out.contains("Failure", ignoreCase = true)) {
                access.exec("pm install-abandon $sessionId")
                return OpResult.fail(ErrorCode.INSTALL_FAILED, "install-commit failed: ${commit.err.ifBlank { commit.out }}")
            }
            return OpResult.success(Unit)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return OpResult.fail(ErrorCode.INSTALL_FAILED, t.message ?: "APK install failed", t)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** Parse "[123]" out of pm install-create's "Success: created install session [123]". */
    private fun parseSessionId(output: String): Int? {
        Regex("\\[(\\d+)]").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        // Fallback: a bare trailing integer.
        return Regex("(\\d+)\\s*$").find(output.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    // ---------------------------------------------------------------------------------------------
    // Streaming + path helpers
    // ---------------------------------------------------------------------------------------------

    /** Stream the current entry's payload into [devicePath] via the elevated write stream. */
    private suspend fun streamEntryToDevice(reader: ArchiveReader, devicePath: String) {
        val parent = devicePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) access.mkdirs(parent)
        access.writeFileStream(devicePath, append = false).use { os ->
            val buf = ByteArray(CHUNK)
            var n = reader.read(buf)
            while (n >= 0) {
                if (n > 0) os.write(buf, 0, n)
                n = reader.read(buf)
            }
            os.flush()
        }
    }

    private suspend fun recreateSymlink(target: String, linkPath: String) {
        val parent = linkPath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) access.mkdirs(parent)
        access.exec("ln -s ${shellQuote(target)} ${shellQuote(linkPath)}")
    }

    /** Read an entry fully into memory (used only for the small manifest). */
    private fun drainEntry(reader: ArchiveReader): ByteArray {
        val sink = java.io.ByteArrayOutputStream()
        val buf = ByteArray(CHUNK)
        var n = reader.read(buf)
        while (n >= 0) {
            if (n > 0) sink.write(buf, 0, n)
            n = reader.read(buf)
        }
        return sink.toByteArray()
    }

    /** Advance past an entry's payload without keeping the bytes. */
    private fun drainEntryDiscard(reader: ArchiveReader) {
        val buf = ByteArray(CHUNK)
        var n = reader.read(buf)
        while (n >= 0) n = reader.read(buf)
    }

    /**
     * Reconstruct the device path from an archive path when the manifest lacks an explicit entry.
     * Mirrors the backup engine's prefix scheme:
     *   private_data/<rel>   -> /data/data/<pkg>/<rel>
     *   obb/<rel>            -> /sdcard/Android/obb/<pkg>/<rel>
     *   external_data/<rel>  -> /sdcard/Android/data/<pkg>/<rel>
     *   media/<rel>          -> no canonical reconstruction (manifest devicePath required)
     */
    private fun reconstructDevicePath(archivePath: String, manifest: BackupManifest, pkg: String): String? {
        val prefix = topPrefix(archivePath)
        val rel = archivePath.substringAfter("$prefix/", "")
        if (rel.isEmpty()) return null
        return when (prefix) {
            AetherPaths.ARCHIVE_DIR_PRIVATE -> "${AetherPaths.privateData(pkg)}/$rel"
            AetherPaths.ARCHIVE_DIR_OBB -> "${AetherPaths.obbDir(pkg)}/$rel"
            AetherPaths.ARCHIVE_DIR_EXTDATA -> "${AetherPaths.externalDataDir(pkg)}/$rel"
            else -> null // MEDIA paths are arbitrary; require the manifest's devicePath.
        }
    }

    private fun normalize(path: String): String = path.trim().trimStart('.', '/').trimEnd('/')

    private fun topPrefix(archivePath: String): String = archivePath.substringBefore('/', archivePath)

    private fun entryLeafName(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** Single-quote a value for safe shell interpolation, escaping embedded single quotes. */
    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /**
     * Guard against a crafted manifest steering writes outside the package's own data roots.
     * `meta.devicePath` is attacker-controlled JSON inside the archive, so a value like
     * `/data/data/com.bank.app/...` or `/system/...` must never be honored. We accept a path
     * only when it is contained (segment-aware) within one of the legitimate roots for [pkg]
     * matching the entry's archive [prefix].
     */
    private fun isDevicePathAllowed(devicePath: String, prefix: String, pkg: String): Boolean {
        val allowedRoots = when (prefix) {
            AetherPaths.ARCHIVE_DIR_PRIVATE -> listOf(
                AetherPaths.privateData(pkg),
                AetherPaths.userPrivateData(pkg),
                AetherPaths.deData(pkg)
            )
            AetherPaths.ARCHIVE_DIR_OBB -> listOf(AetherPaths.obbDir(pkg))
            AetherPaths.ARCHIVE_DIR_EXTDATA -> listOf(AetherPaths.externalDataDir(pkg))
            // MEDIA paths are user content on shared storage; confine to /sdcard.
            AetherPaths.ARCHIVE_DIR_MEDIA -> listOf("/sdcard", "/storage/emulated/0")
            else -> return false
        }
        // Reject any `..` traversal before comparing, then require a real root containment.
        if (devicePath.split('/').any { it == ".." }) return false
        return allowedRoots.any { root ->
            devicePath == root || devicePath.startsWith("$root/")
        }
    }
}
