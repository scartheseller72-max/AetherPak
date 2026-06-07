package com.zorg.aetherpak.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorg.aetherpak.ServiceLocator
import com.zorg.aetherpak.common.AccessCapabilities
import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.common.AppEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val apps: List<AppEntry> = emptyList(),
    val query: String = "",
    val showSystem: Boolean = false,
    val hasAccess: Boolean = false,
    val mode: AccessMode = AccessMode.NONE,
    val capabilities: AccessCapabilities? = null
) {
    val filtered: List<AppEntry>
        get() = apps.filter { app ->
            (showSystem || !app.isSystemApp) &&
                (query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true))
        }

    val shizukuOnly: Boolean
        get() = capabilities != null && !capabilities.canReadPrivateData &&
            capabilities.mode == AccessMode.SHIZUKU
}

class HomeViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val provider = locator.currentProvider
        if (provider == null) {
            _state.update {
                it.copy(loading = false, hasAccess = false, mode = AccessMode.NONE, capabilities = null)
            }
            return
        }
        _state.update {
            it.copy(
                loading = true,
                error = null,
                hasAccess = true,
                mode = provider.mode,
                capabilities = provider.capabilities
            )
        }
        viewModelScope.launch {
            try {
                val apps = locator.requireScanner().scanInstalledApps(includeSystem = true)
                _state.update { it.copy(loading = false, apps = apps) }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Scan failed") }
            }
        }
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun onToggleSystem(value: Boolean) = _state.update { it.copy(showSystem = value) }
}
