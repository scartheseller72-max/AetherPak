package com.zorg.aetherpak.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zorg.aetherpak.R
import com.zorg.aetherpak.common.AccessCapabilities
import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.common.CodecType
import com.zorg.aetherpak.ui.AetherViewModelFactory
import com.zorg.aetherpak.ui.components.CodecSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAccessSetup: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AetherViewModelFactory())
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard(title = stringResource(R.string.settings_access_mode)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccessMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.preferredAccessMode == mode,
                            onClick = { viewModel.setPreferredMode(mode) },
                            label = { Text(mode.name) }
                        )
                    }
                }
                OutlinedButton(onClick = onAccessSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.title_access_setup))
                }
            }

            SectionCard(title = stringResource(R.string.settings_default_codec)) {
                CodecSelector(
                    selected = state.defaultCodec,
                    onSelect = { viewModel.setCodec(it) }
                )
            }

            SectionCard(title = stringResource(R.string.settings_default_toggles)) {
                ToggleRow(stringResource(R.string.backup_include_private), state.includePrivateData, viewModel::setIncludePrivate)
                ToggleRow(stringResource(R.string.backup_include_obb), state.includeObb, viewModel::setIncludeObb)
                ToggleRow(stringResource(R.string.backup_include_external), state.includeExternalData, viewModel::setIncludeExternal)
            }

            SectionCard(title = stringResource(R.string.settings_output_dir)) {
                OutlinedTextField(
                    value = state.outputDir,
                    onValueChange = { viewModel.setOutputDir(it) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Default app external dir") }
                )
            }

            SectionCard(title = stringResource(R.string.settings_capabilities)) {
                CapabilitiesContent(state.currentCapabilities)
            }

            SectionCard(title = stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                Text("AetherPak v1.0.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CapabilitiesContent(caps: AccessCapabilities?) {
    if (caps == null) {
        Text(stringResource(R.string.cap_none_desc), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val (title, desc) = when (caps.mode) {
        AccessMode.ROOT -> stringResource(R.string.cap_root_title) to stringResource(R.string.cap_root_desc)
        AccessMode.SHIZUKU -> stringResource(R.string.cap_shizuku_title) to stringResource(R.string.cap_shizuku_desc)
        AccessMode.NONE -> stringResource(R.string.cap_none_title) to stringResource(R.string.cap_none_desc)
    }
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    CapLine("Read private data", caps.canReadPrivateData)
    CapLine("Write private data", caps.canWritePrivateData)
    CapLine("Change ownership", caps.canChangeOwnership)
    CapLine("Restore SELinux context", caps.canRestoreSeContext)
    CapLine("Install packages", caps.canInstallPackages)
    CapLine("Read OBB / media", caps.canReadObb)
    CapLine("Full backup supported", caps.supportsFullBackup)
}

@Composable
private fun CapLine(label: String, value: Boolean) {
    Text(
        text = "${if (value) "✓" else "✗"}  $label",
        style = MaterialTheme.typography.bodyMedium,
        color = if (value) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
