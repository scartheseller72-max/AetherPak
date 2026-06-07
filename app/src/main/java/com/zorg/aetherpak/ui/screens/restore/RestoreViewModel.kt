package com.zorg.aetherpak.ui.screens.restore

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorg.aetherpak.ServiceLocator
import com.zorg.aetherpak.common.BackupManifest
import com.zorg.aetherpak.common.OpResult
import com.zorg.aetherpak.common.OperationProgress
import com.zorg.aetherpak.common.RestoreRequest
import com.zorg.aetherpak.data.BackupRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RestoreUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val selectedArchivePath: String? = null,
    val manifest: BackupManifest? = null,
    val running: Boolean = false,
    val progress: OperationProgress? = null,
    val done: Boolean = false,
    val warnings: List<String> = emptyList(),
    val resultMessage: String? = null
) {
    val isPartial: Boolean get() = manifest?.isPartial == true
}

class RestoreViewModel(private val locator: ServiceLocator) : ViewModel() {

    val localBackups: StateFlow<List<BackupRecord>> =
        locator.backupRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    fun selectLocal(record: BackupRecord) = previewArchive(record.archivePath)

    /** Copy a picked external archive into cache and preview its manifest. */
    fun selectExternal(uri: Uri) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val path = copyToCache(locator.appContext, uri)
                previewArchive(path)
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Could not read file") }
            }
        }
    }

    private fun previewArchive(path: String) {
        _state.update { it.copy(loading = true, error = null, selectedArchivePath = path, done = false) }
        viewModelScope.launch {
            when (val result = locator.requireRestoreEngine().readManifest(path)) {
                is OpResult.Success -> _state.update {
                    it.copy(loading = false, manifest = result.value)
                }
                is OpResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error.message, manifest = null)
                }
            }
        }
    }

    fun startRestore() {
        val current = _state.value
        val path = current.selectedArchivePath ?: return
        if (current.running) return
        _state.update { it.copy(running = true, error = null, done = false) }
        viewModelScope.launch {
            try {
                val caps = locator.requireProvider().capabilities
                val request = RestoreRequest(
                    archivePath = path,
                    installApk = true,
                    restorePrivateData = caps.canWritePrivateData,
                    restoreObb = true,
                    restoreExternalData = true
                )
                val result = locator.requireRestoreEngine().restore(request) { p ->
                    _state.update { it.copy(progress = p) }
                }
                when (result) {
                    is OpResult.Success -> _state.update {
                        it.copy(
                            running = false,
                            done = true,
                            warnings = result.value.warnings,
                            resultMessage = "Restored ${result.value.filesRestored} files for " +
                                result.value.packageName
                        )
                    }
                    is OpResult.Failure -> _state.update {
                        it.copy(running = false, error = result.error.message)
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(running = false, error = t.message ?: "Restore failed") }
            }
        }
    }

    private suspend fun copyToCache(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "imported").apply { mkdirs() }
        val out = File(dir, "import_${System.currentTimeMillis()}.ark")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open selected file")
        out.absolutePath
    }
}
