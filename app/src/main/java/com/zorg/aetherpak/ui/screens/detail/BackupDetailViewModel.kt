package com.zorg.aetherpak.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorg.aetherpak.ServiceLocator
import com.zorg.aetherpak.common.BackupManifest
import com.zorg.aetherpak.common.OpResult
import com.zorg.aetherpak.data.BackupRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val record: BackupRecord? = null,
    val manifest: BackupManifest? = null
)

class BackupDetailViewModel(
    private val locator: ServiceLocator,
    private val backupId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(BackupDetailUiState())
    val state: StateFlow<BackupDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val record = locator.backupRepository.getById(backupId)
            if (record == null) {
                _state.update { it.copy(loading = false, error = "Backup not found") }
                return@launch
            }
            _state.update { it.copy(record = record) }
            // Best-effort manifest read; engine may be unavailable if no provider selected.
            try {
                when (val result = locator.requireRestoreEngine().readManifest(record.archivePath)) {
                    is OpResult.Success -> _state.update {
                        it.copy(loading = false, manifest = result.value)
                    }
                    is OpResult.Failure -> _state.update {
                        it.copy(loading = false, error = result.error.message)
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message) }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            locator.backupRepository.remove(backupId)
            onDeleted()
        }
    }
}
