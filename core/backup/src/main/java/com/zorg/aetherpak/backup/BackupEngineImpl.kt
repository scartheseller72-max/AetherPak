package com.zorg.aetherpak.backup

import android.content.Context
import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.AetherPaths
import com.zorg.aetherpak.common.ArchiveEntryHeader
import com.zorg.aetherpak.common.ArchiveWriter
import com.zorg.aetherpak.common.BackupComponent
import com.zorg.aetherpak.common.BackupComponentType
import com.zorg.aetherpak.common.BackupEngine
import com.zorg.aetherpak.common.BackupManifest
import com.zorg.aetherpak.common.BackupOutcome
import com.zorg.aetherpak.common.BackupRequest
import com.zorg.aetherpak.common.ErrorCode
import com.zorg.aetherpak.common.ManifestEntry
import com.zorg.aetherpak.common.NativeArchive
import com.zorg.aetherpak.common.OpResult
import com.zorg.aetherpak.common.OperationProgress
import com.zorg.aetherpak.common.ProgressCallback
import com.zorg.aetherpak.compress.AetherArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Default [BackupEngine]. Produces a single `.ark` container per app holding the APK(s), optional
 * OBB and external-data trees, optional private `/data/data` tree (ROOT only), and a `manifest.json`
 * written as the FINAL archive entry so the restore engine can locate provenance without scanning
 * payload.
 *
 * Archive layout (see [AetherPaths]):
 *   apk/<base + splits>
 *   obb/<relative tree>
 *   external_data/<relative tree>
 *   private_data/<relative tree, cache/code_cache/no_backup/lib excluded>
 *   manifest.json   (always last)
 */
class BackupEngineImpl(
    private val context: Context,
    private val access: AccessProvider,
    private val archive: NativeArchive = AetherArchive
) : BackupEngine {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun backup(
        request: BackupRequest,
        progress: ProgressCallback
    ): OpResult<BackupOutcome> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val app = request.app
        try {
            // ---------- PREPARING ----------
            progress(OperationProgress(OperationProgress.Phase.PREPARING, message = "Validating access"))
            coroutineContext.ensureActive()

            val caps = access.capabilities
            val notes = StringBuilder()
            var isPartial = false

            // Determine whether private data can actually be captured.
            val privateRequested = request.includePrivateData
            val privateAllowed = privateRequested && caps.canReadPrivateData
            if (privateRequested && !caps.canReadPrivateData) {
                isPartial = true
                progress(
                    OperationProgress(
                        OperationProgress.Phase.PREPARING,
                        message = "Private app data unavailable in ${caps.mode} mode; producing a partial backup"
                    )
                )
            }

            // ---------- SCANNING ----------
            progress(OperationProgress(OperationProgress.Phase.SCANNING, message = "Building file list"))
            coroutineContext.ensureActive()

            val planner = BackupPlanner(access)
            val componentPlans = ArrayList<ComponentPlan>()
            val components = ArrayList<BackupComponent>()

            // APK (always included).
            val apkPlan = planner.planApks(app.baseApkPath, app.splitApkPaths)
            if (apkPlan.entries.isEmpty()) {
                return@withContext OpResult.fail(
                    ErrorCode.PACKAGE_NOT_FOUND,
                    "No APK files found for ${app.packageName} (base=${app.baseApkPath})"
                )
            }
            componentPlans.add(apkPlan)
            val baseCount = apkPlan.entries.count { it.type == BackupComponentType.BASE_APK }
            val splitCount = apkPlan.entries.count { it.type == BackupComponentType.SPLIT_APK }
            components.add(
                BackupComponent(
                    type = BackupComponentType.BASE_APK,
                    included = true,
                    fileCount = baseCount,
                    totalBytes = apkPlan.entries.filter { it.type == BackupComponentType.BASE_APK }
                        .sumOf { it.node.size },
                    rootPathOnDevice = app.baseApkPath
                )
            )
            if (splitCount > 0) {
                components.add(
                    BackupComponent(
                        type = BackupComponentType.SPLIT_APK,
                        included = true,
                        fileCount = splitCount,
                        totalBytes = apkPlan.entries.filter { it.type == BackupComponentType.SPLIT_APK }
                            .sumOf { it.node.size },
                        rootPathOnDevice = app.baseApkPath
                    )
                )
            }

            // OBB.
            if (request.includeObb && caps.canReadObb && safeExists(app.obbDir)) {
                val obbPlan = planner.planTree(
                    rootPath = app.obbDir,
                    archiveDir = AetherPaths.ARCHIVE_DIR_OBB,
                    type = BackupComponentType.OBB
                )
                componentPlans.add(obbPlan)
                components.add(obbComponent(obbPlan, included = true, app.obbDir))
            } else if (request.includeObb) {
                components.add(
                    BackupComponent(
                        type = BackupComponentType.OBB,
                        included = false,
                        fileCount = 0,
                        totalBytes = 0,
                        rootPathOnDevice = app.obbDir,
                        skippedReason = if (!caps.canReadObb) {
                            "OBB not readable in ${caps.mode} mode"
                        } else {
                            "No OBB directory present"
                        }
                    )
                )
            }

            // EXTERNAL DATA (/sdcard/Android/data/<pkg>).
            if (request.includeExternalData && caps.canReadObb && safeExists(app.externalDataDir)) {
                val extPlan = planner.planTree(
                    rootPath = app.externalDataDir,
                    archiveDir = AetherPaths.ARCHIVE_DIR_EXTDATA,
                    type = BackupComponentType.EXTERNAL_DATA
                )
                componentPlans.add(extPlan)
                components.add(
                    BackupComponent(
                        type = BackupComponentType.EXTERNAL_DATA,
                        included = true,
                        fileCount = extPlan.fileCount,
                        totalBytes = extPlan.totalBytes,
                        rootPathOnDevice = app.externalDataDir
                    )
                )
            } else if (request.includeExternalData) {
                components.add(
                    BackupComponent(
                        type = BackupComponentType.EXTERNAL_DATA,
                        included = false,
                        fileCount = 0,
                        totalBytes = 0,
                        rootPathOnDevice = app.externalDataDir,
                        skippedReason = "No external data directory present or not readable"
                    )
                )
            }

            // PRIVATE DATA (/data/data/<pkg>) — ROOT only.
            if (privateAllowed && safeExists(app.dataDir)) {
                val privPlan = planner.planTree(
                    rootPath = app.dataDir,
                    archiveDir = AetherPaths.ARCHIVE_DIR_PRIVATE,
                    type = BackupComponentType.PRIVATE_DATA,
                    excludedTopLevel = AetherPaths.EXCLUDED_PRIVATE_SUBDIRS
                )
                componentPlans.add(privPlan)
                components.add(
                    BackupComponent(
                        type = BackupComponentType.PRIVATE_DATA,
                        included = true,
                        fileCount = privPlan.fileCount,
                        totalBytes = privPlan.totalBytes,
                        rootPathOnDevice = app.dataDir
                    )
                )
            } else if (privateRequested) {
                // Either no access (Shizuku) or the dir is missing.
                val reason = if (!caps.canReadPrivateData) {
                    "Shizuku cannot read private app data; root required"
                } else {
                    "Private data directory missing"
                }
                if (!caps.canReadPrivateData) isPartial = true
                components.add(
                    BackupComponent(
                        type = BackupComponentType.PRIVATE_DATA,
                        included = false,
                        fileCount = 0,
                        totalBytes = 0,
                        rootPathOnDevice = app.dataDir,
                        skippedReason = reason
                    )
                )
            }

            val plannedEntries = componentPlans.flatMap { it.entries }
            val totalFiles = plannedEntries.count { !it.node.isDirectory && !it.node.isSymlink }
            val totalBytes = plannedEntries.sumOf {
                if (!it.node.isDirectory && !it.node.isSymlink) it.node.size else 0L
            }

            // ---------- COMPRESSING ----------
            if (!archive.isAvailable()) {
                return@withContext OpResult.fail(
                    ErrorCode.COMPRESSION_FAILED,
                    "Native archive backend unavailable"
                )
            }

            val outFile = File(request.outputDir, AetherPaths.archiveFileName(app.packageName, app.versionCode))
            val parent = outFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return@withContext OpResult.fail(
                    ErrorCode.IO_ERROR,
                    "Cannot create output directory ${parent.absolutePath}"
                )
            }
            val outPath = outFile.absolutePath

            val manifestEntries = ArrayList<ManifestEntry>(plannedEntries.size)
            var processedBytes = 0L
            var processedFiles = 0

            val buffer = ByteArray(CHUNK_SIZE)
            var writer: ArchiveWriter? = null
            try {
                writer = archive.openWriter(outPath, request.codec, request.compressionLevel)
                val w = writer

                for (planned in plannedEntries) {
                    coroutineContext.ensureActive()
                    val node = planned.node

                    val header = ArchiveEntryHeader(
                        path = planned.archivePath,
                        size = if (node.isDirectory || node.isSymlink) 0L else node.size,
                        mode = node.mode,
                        isDirectory = node.isDirectory,
                        isSymlink = node.isSymlink,
                        linkTarget = node.linkTarget
                    )
                    w.putEntry(header)

                    // Only regular files carry payload. Do NOT follow symlinks.
                    if (!node.isDirectory && !node.isSymlink) {
                        streamFile(node.path, w, buffer) { wrote ->
                            processedBytes += wrote
                            // Throttle progress to keep callback overhead low.
                            if (processedBytes - lastEmittedBytes >= PROGRESS_BYTE_STEP) {
                                lastEmittedBytes = processedBytes
                                progress(
                                    OperationProgress(
                                        phase = OperationProgress.Phase.COMPRESSING,
                                        currentFile = planned.archivePath,
                                        processedBytes = processedBytes,
                                        totalBytes = totalBytes,
                                        processedFiles = processedFiles,
                                        totalFiles = totalFiles
                                    )
                                )
                            }
                        }
                        processedFiles++
                    }
                    w.closeEntry()

                    manifestEntries.add(
                        ManifestEntry(
                            archivePath = planned.archivePath,
                            devicePath = node.path,
                            type = planned.type,
                            isDirectory = node.isDirectory,
                            isSymlink = node.isSymlink,
                            linkTarget = node.linkTarget,
                            size = node.size,
                            mode = node.mode,
                            uid = node.uid,
                            gid = node.gid,
                            seContext = node.seContext
                        )
                    )
                }

                // ---------- MANIFEST (final entry) ----------
                progress(
                    OperationProgress(
                        phase = OperationProgress.Phase.COMPRESSING,
                        currentFile = BackupManifest.MANIFEST_FILENAME,
                        processedBytes = processedBytes,
                        totalBytes = totalBytes,
                        processedFiles = processedFiles,
                        totalFiles = totalFiles,
                        message = "Writing manifest"
                    )
                )

                val manifest = buildManifest(request, components, manifestEntries, isPartial, notes.toString())
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                w.putEntry(
                    ArchiveEntryHeader(
                        path = BackupManifest.MANIFEST_FILENAME,
                        size = manifestBytes.size.toLong(),
                        mode = MANIFEST_MODE,
                        isDirectory = false,
                        isSymlink = false,
                        linkTarget = null
                    )
                )
                w.write(manifestBytes, manifestBytes.size)
                w.closeEntry()

                w.close()
                writer = null

                progress(
                    OperationProgress(
                        phase = OperationProgress.Phase.DONE,
                        processedBytes = totalBytes,
                        totalBytes = totalBytes,
                        processedFiles = totalFiles,
                        totalFiles = totalFiles,
                        message = if (isPartial) "Partial backup complete" else "Backup complete"
                    )
                )

                val durationMs = System.currentTimeMillis() - startedAt
                return@withContext OpResult.success(
                    BackupOutcome(
                        archivePath = outPath,
                        manifest = manifest,
                        totalBytes = totalBytes,
                        durationMs = durationMs
                    )
                )
            } finally {
                if (writer != null) {
                    // An exception escaped mid-write; close defensively and drop the partial file.
                    runCatching { writer.close() }
                    runCatching { File(outPath).takeIf { it.exists() }?.delete() }
                }
                lastEmittedBytes = 0L
            }
        } catch (ce: CancellationException) {
            progress(OperationProgress(OperationProgress.Phase.FAILED, message = "Cancelled"))
            throw ce
        } catch (io: IOException) {
            progress(OperationProgress(OperationProgress.Phase.FAILED, message = io.message))
            OpResult.fail(ErrorCode.IO_ERROR, io.message ?: "I/O failure during backup", io)
        } catch (t: Throwable) {
            progress(OperationProgress(OperationProgress.Phase.FAILED, message = t.message))
            OpResult.fail(ErrorCode.COMPRESSION_FAILED, t.message ?: "Backup failed", t)
        }
    }

    private var lastEmittedBytes = 0L

    /** Streams one file from the elevated provider into [writer] in [CHUNK_SIZE] chunks. */
    private suspend fun streamFile(
        devicePath: String,
        writer: ArchiveWriter,
        buffer: ByteArray,
        onChunk: (Int) -> Unit
    ) {
        val input = access.readFileStream(devicePath)
        input.use { stream ->
            while (true) {
                coroutineContext.ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    writer.write(buffer, n)
                    onChunk(n)
                }
            }
        }
    }

    private fun obbComponent(plan: ComponentPlan, included: Boolean, root: String): BackupComponent =
        BackupComponent(
            type = BackupComponentType.OBB,
            included = included,
            fileCount = plan.fileCount,
            totalBytes = plan.totalBytes,
            rootPathOnDevice = root
        )

    private fun buildManifest(
        request: BackupRequest,
        components: List<BackupComponent>,
        entries: List<ManifestEntry>,
        isPartial: Boolean,
        notes: String
    ): BackupManifest {
        val app = request.app
        // Derive the app's primary GID from the first private/data entry when available,
        // otherwise fall back to the UID (Android assigns matching app uid/gid by default).
        val sourceGid = entries.firstOrNull {
            it.type == BackupComponentType.PRIVATE_DATA && !it.isDirectory
        }?.gid ?: entries.firstOrNull { it.type == BackupComponentType.PRIVATE_DATA }?.gid
            ?: app.uid

        return BackupManifest(
            packageName = app.packageName,
            appLabel = app.label,
            versionName = app.versionName,
            versionCode = app.versionCode,
            minSdk = 0,
            targetSdk = 0,
            createdAtEpochMs = System.currentTimeMillis(),
            createdByMode = access.mode.name,
            codec = request.codec.id,
            sourceUid = app.uid,
            sourceGid = sourceGid,
            components = components,
            entries = entries,
            isPartial = isPartial,
            notes = notes.ifBlank { null }
        )
    }

    private suspend fun safeExists(path: String): Boolean =
        runCatching { access.exists(path) }.getOrDefault(false)

    companion object {
        private const val CHUNK_SIZE = 256 * 1024 // 256 KB streaming chunk
        private const val PROGRESS_BYTE_STEP = 4L * 1024 * 1024 // emit progress every ~4 MB
        private const val MANIFEST_MODE = 0b110_100_100 // 0o644
    }
}
