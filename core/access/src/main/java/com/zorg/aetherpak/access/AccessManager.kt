package com.zorg.aetherpak.access

import android.content.Context
import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.common.AccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry point the rest of AetherPak uses to discover and instantiate a privilege backend.
 *
 * Detection is non-destructive: [detectAvailable] only probes for the presence/reachability of a
 * backend (su binary, running Shizuku service) and never triggers a permission prompt. Acquiring
 * the actual grant is the caller's job via [AccessProvider.requestAccess].
 *
 * @param context application context, forwarded to the Shizuku provider.
 */
class AccessManager(private val context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    /**
     * Probe which backends are reachable on this device, in preference order (ROOT first). Does
     * not request any grant. Returns an empty list when no elevation is available.
     */
    suspend fun detectAvailable(): List<AccessMode> = withContext(Dispatchers.IO) {
        val modes = ArrayList<AccessMode>(2)

        val root = RootAccessProvider()
        if (runCatching { root.isAvailable() }.getOrDefault(false)) {
            modes.add(AccessMode.ROOT)
        }

        val shizuku = ShizukuAccessProvider(appContext)
        if (runCatching { shizuku.isAvailable() }.getOrDefault(false)) {
            modes.add(AccessMode.SHIZUKU)
        }

        modes
    }

    /**
     * Construct a provider for an explicit [mode]. NONE has no provider implementation and is
     * rejected, since callers asking for NONE have no elevated operation to perform.
     */
    fun create(mode: AccessMode): AccessProvider = when (mode) {
        AccessMode.ROOT -> RootAccessProvider()
        AccessMode.SHIZUKU -> ShizukuAccessProvider(appContext)
        AccessMode.NONE -> throw IllegalArgumentException("AccessMode.NONE has no AccessProvider")
    }

    /**
     * Pick the best available backend (ROOT > SHIZUKU > null). Returns null when nothing is
     * reachable. The returned provider is constructed but NOT yet granted — call requestAccess().
     */
    suspend fun best(): AccessProvider? {
        val available = detectAvailable()
        return when {
            available.contains(AccessMode.ROOT) -> create(AccessMode.ROOT)
            available.contains(AccessMode.SHIZUKU) -> create(AccessMode.SHIZUKU)
            else -> null
        }
    }
}
