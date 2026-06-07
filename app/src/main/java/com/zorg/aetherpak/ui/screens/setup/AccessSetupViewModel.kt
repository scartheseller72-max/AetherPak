package com.zorg.aetherpak.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorg.aetherpak.ServiceLocator
import com.zorg.aetherpak.common.AccessMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccessSetupUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val available: List<AccessMode> = emptyList(),
    val grantedMode: AccessMode? = null,
    val requesting: Boolean = false
)

class AccessSetupViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(AccessSetupUiState())
    val state: StateFlow<AccessSetupUiState> = _state.asStateFlow()

    init {
        detect()
        locator.currentProvider?.let { p -> _state.update { it.copy(grantedMode = p.mode) } }
    }

    fun detect() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val modes = locator.accessManager.detectAvailable()
                _state.update { it.copy(loading = false, available = modes) }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Detection failed") }
            }
        }
    }

    /** Request the grant for [mode]; on success wire the provider into the ServiceLocator. */
    fun request(mode: AccessMode) {
        if (mode == AccessMode.NONE) return
        _state.update { it.copy(requesting = true, error = null) }
        viewModelScope.launch {
            try {
                val provider = locator.accessManager.create(mode)
                val granted = provider.requestAccess()
                if (granted) {
                    locator.setAccessProvider(provider)
                    locator.settingsStore.setPreferredAccessMode(mode)
                    _state.update { it.copy(requesting = false, grantedMode = mode) }
                } else {
                    _state.update { it.copy(requesting = false, error = "Grant was denied for $mode") }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(requesting = false, error = t.message ?: "Request failed") }
            }
        }
    }
}
