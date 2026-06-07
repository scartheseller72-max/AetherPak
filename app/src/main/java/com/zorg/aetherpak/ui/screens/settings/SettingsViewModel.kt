package com.zorg.aetherpak.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorg.aetherpak.ServiceLocator
import com.zorg.aetherpak.common.AccessCapabilities
import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.common.CodecType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val preferredAccessMode: AccessMode = AccessMode.NONE,
    val defaultCodec: CodecType = CodecType.ZSTD,
    val includePrivateData: Boolean = true,
    val includeObb: Boolean = true,
    val includeExternalData: Boolean = true,
    val outputDir: String = "",
    val currentCapabilities: AccessCapabilities? = null
)

class SettingsViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        val s = locator.settingsStore
        viewModelScope.launch {
            combine(
                s.preferredAccessMode,
                s.defaultCodec,
                s.includePrivateData,
                s.includeObb,
                s.includeExternalData
            ) { mode, codec, priv, obb, ext ->
                arrayOf(mode, codec, priv, obb, ext)
            }.combine(s.backupOutputDir) { base, dir ->
                Pair(base, dir)
            }.collect { (base, dir) ->
                _state.update {
                    it.copy(
                        loading = false,
                        preferredAccessMode = base[0] as AccessMode,
                        defaultCodec = base[1] as CodecType,
                        includePrivateData = base[2] as Boolean,
                        includeObb = base[3] as Boolean,
                        includeExternalData = base[4] as Boolean,
                        outputDir = dir,
                        currentCapabilities = locator.currentProvider?.capabilities
                    )
                }
            }
        }
    }

    fun setPreferredMode(mode: AccessMode) = viewModelScope.launch {
        locator.settingsStore.setPreferredAccessMode(mode)
    }

    fun setCodec(codec: CodecType) = viewModelScope.launch {
        locator.settingsStore.setDefaultCodec(codec)
    }

    fun setIncludePrivate(value: Boolean) = viewModelScope.launch {
        locator.settingsStore.setIncludePrivateData(value)
    }

    fun setIncludeObb(value: Boolean) = viewModelScope.launch {
        locator.settingsStore.setIncludeObb(value)
    }

    fun setIncludeExternal(value: Boolean) = viewModelScope.launch {
        locator.settingsStore.setIncludeExternalData(value)
    }

    fun setOutputDir(dir: String) = viewModelScope.launch {
        locator.settingsStore.setBackupOutputDir(dir)
    }
}
