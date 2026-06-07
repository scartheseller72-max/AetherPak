package com.zorg.aetherpak.ui.screens.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorg.aetherpak.ServiceLocator
import com.zorg.aetherpak.common.AccessCapabilities
import com.zorg.aetherpak.common.AppEntry
import com.zorg.aetherpak.common.BackupRequest
import com.zorg.aetherpak.common.CodecType
import com.zorg.aetherpak.common.OpResult
import com.zorg.aetherpak.common.OperationProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class BackupUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val app: AppEntry? = null,
    val capabilities: AccessCapabilities? = null,
    val codec: CodecType = CodecType.ZSTD,
    val includePrivateData: Boolean = true,
    val includeObb: Boolean = true,
    val includeExternalData: Boolean = true,
    val running: Boolean = false,
    val progress: OperationProgress? = null,
    val done: Boolean = false,
    val resultMessage: String? = null
) {
    val canReadPrivateData: Boolean get() = capabilities?.canReadPrivateData == true
    val willBePartial: Boolean get() = !canReadPrivateData || !includePrivateData
}

class BackupViewModel(
    private val locator: ServiceLocator,
    private val pkg: String
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val provider = locator.currentProvider
        if (provider == null) {
            _state.update { it.copy(loading = false, error = "No access mode selected.") }
            return
        }
        val caps = provider.capabilities
        viewModelScope.launch {
            try {
                val app = locator.requireScanner().scanSingle(pkg)
                val settings = locator.settingsStore
                val codec = settings.defaultCodec.first()
                val incPriv = settings.includePrivateData.first() && caps.canReadPrivateData
                val incObb = settings.includeObb.first()
                val incExt = settings.includeExternalData.first()
                _state.update {
                    it.copy(
                        loading = false,
                        app = app,
                        error = if (app == null) "App not found: $pkg" else null,
                        capabilities = caps,
                        codec = codec,
                        includePrivateData = incPriv,
                        includeObb = incObb,
                        includeExternalData = incExt
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Load failed") }
            }
        }
    }

    fun setCodec(codec: CodecType) = _state.update { it.copy(codec = codec) }

    fun setIncludePrivate(value: Boolean) {
        if (_state.value.canReadPrivateData) _state.update { it.copy(includePrivateData = value) }
    }

    fun setIncludeObb(value: Boolean) = _state.update { it.copy(includeObb = value) }

    fun setIncludeExternal(value: Boolean) = _state.update { it.copy(includeExternalData = value) }

    fun startBackup() {
        val current = _state.value
        val app = current.app ?: return
        if (current.running) return
        _state.update { it.copy(running = true, error = null, done = false, resultMessage = null) }

        viewModelScope.launch {
            try {
                val outputDir = resolveOutputDir()
                val request = BackupRequest(
                    app = app,
                    codec = current.codec,
                    includePrivateData = current.includePrivateData && current.canReadPrivateData,
                    includeObb = current.includeObb,
                    includeExternalData = current.includeExternalData,
                    outputDir = outputDir
                )
                val engine = locator.requireBackupEngine()
                val result = engine.backup(request) { p ->
                    _state.update { it.copy(progress = p) }
                }
                when (result) {
                    is OpResult.Success -> {
                        locator.backupRepository.record(result.value, current.capabilities!!.mode)
                        _state.update {
                            it.copy(
                                running = false,
                                done = true,
                                resultMessage = "Backup complete: ${result.value.archivePath}"
                            )
                        }
                    }
                    is OpResult.Failure -> _state.update {
                        it.copy(running = false, error = result.error.message)
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(running = false, error = t.message ?: "Backup failed") }
            }
        }
    }

    private suspend fun resolveOutputDir(): String {
        val configured = locator.settingsStore.backupOutputDir.first()
        if (configured.isNotBlank()) return configured
        val fallback = File(locator.appContext.getExternalFilesDir(null), "backups")
        fallback.mkdirs()
        return fallback.absolutePath
    }
}
