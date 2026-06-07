package com.zorg.aetherpak.backup

import com.zorg.aetherpak.common.AccessProvider
import com.zorg.aetherpak.common.AetherPaths
import com.zorg.aetherpak.common.BackupComponentType
import com.zorg.aetherpak.common.FileNode
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * A single planned archive entry: the source [node] on the device, the [archivePath] it will be
 * stored under inside the .ark container, and the logical [type] it belongs to. No file payload
 * is read at planning time — the engine streams bytes during the COMPRESSING phase.
 */
internal data class PlannedEntry(
    val node: FileNode,
    val archivePath: String,
    val type: BackupComponentType
)

/**
 * Result of planning one component (APK / OBB / external data / private data): the ordered list
 * of entries plus aggregate counts used for progress reporting and manifest [BackupComponent]s.
 */
internal data class ComponentPlan(
    val type: BackupComponentType,
    val rootPathOnDevice: String,
    val entries: List<PlannedEntry>
) {
    val fileCount: Int get() = entries.count { !it.node.isDirectory && !it.node.isSymlink }
    val totalBytes: Long get() = entries.sumOf { if (!it.node.isDirectory && !it.node.isSymlink) it.node.size else 0L }
}

/**
 * Turns device paths into ordered [PlannedEntry] lists. Directories and symlinks are preserved as
 * zero-payload entries; symlinks are NOT followed into the payload (their target string is kept so
 * restore can recreate the link). Excluded private subdirs (cache/code_cache/no_backup/lib) are
 * pruned from the private-data walk.
 */
internal class BackupPlanner(private val access: AccessProvider) {

    /**
     * Plan the APK component from explicit base + split paths. These are flat files placed directly
     * under [AetherPaths.ARCHIVE_DIR_APK] keyed by their base filename.
     */
    suspend fun planApks(baseApkPath: String, splitApkPaths: List<String>): ComponentPlan {
        val entries = ArrayList<PlannedEntry>()
        val seenNames = HashSet<String>()
        val allApks = buildList {
            if (baseApkPath.isNotEmpty()) add(baseApkPath)
            addAll(splitApkPaths)
        }
        for (path in allApks) {
            coroutineContext.ensureActive()
            val node = access.stat(path) ?: continue
            if (node.isDirectory) continue
            val name = uniqueName(node.name.ifEmpty { fileName(path) }, seenNames)
            val archivePath = "${AetherPaths.ARCHIVE_DIR_APK}/$name"
            entries.add(PlannedEntry(node, archivePath, BackupComponentType.BASE_APK))
        }
        // Reclassify everything after the first as SPLIT_APK; first is the base.
        val typed = entries.mapIndexed { index, entry ->
            if (index == 0) entry else entry.copy(type = BackupComponentType.SPLIT_APK)
        }
        return ComponentPlan(BackupComponentType.BASE_APK, baseApkPath, typed)
    }

    /**
     * Plan a tree-based component (OBB / external data / private data) by walking [rootPath] and
     * mapping each node to "[archiveDir]/<relative-path>". When [excludedTopLevel] is non-empty,
     * any node whose first path segment under the root matches an excluded name is dropped.
     */
    suspend fun planTree(
        rootPath: String,
        archiveDir: String,
        type: BackupComponentType,
        excludedTopLevel: Set<String> = emptySet()
    ): ComponentPlan {
        val nodes = access.walk(rootPath)
        val normalizedRoot = rootPath.trimEnd('/')
        val entries = ArrayList<PlannedEntry>(nodes.size)
        for (node in nodes) {
            coroutineContext.ensureActive()
            val relative = relativize(normalizedRoot, node.path) ?: continue
            if (relative.isEmpty()) continue
            if (excludedTopLevel.isNotEmpty()) {
                val firstSegment = relative.substringBefore('/')
                if (firstSegment in excludedTopLevel) continue
            }
            val archivePath = "$archiveDir/$relative"
            entries.add(PlannedEntry(node, archivePath, type))
        }
        return ComponentPlan(type, rootPath, entries)
    }

    private fun relativize(root: String, path: String): String? {
        val p = path.trimEnd('/')
        if (p == root) return ""
        val prefix = "$root/"
        if (!p.startsWith(prefix)) return null
        return p.substring(prefix.length)
    }

    private fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun uniqueName(name: String, seen: MutableSet<String>): String {
        if (seen.add(name)) return name
        var i = 1
        while (true) {
            val candidate = "${i}_$name"
            if (seen.add(candidate)) return candidate
            i++
        }
    }
}
