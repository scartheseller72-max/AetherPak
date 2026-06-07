package com.zorg.aetherpak.ui.screens.backup

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.zorg.aetherpak.ui.AetherViewModelFactory
import com.zorg.aetherpak.ui.components.CapabilityBadge
import com.zorg.aetherpak.ui.components.CodecSelector
import com.zorg.aetherpak.ui.components.OperationProgressBar
import com.zorg.aetherpak.ui.components.WarningBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    pkg: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: BackupViewModel = viewModel(factory = AetherViewModelFactory(pkg = pkg))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.app?.label ?: stringResource(R.string.title_backup)) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when {
                state.loading -> CircularProgressIndicator()
                state.error != null && state.app == null -> WarningBanner(text = state.error ?: "")
                else -> {
                    state.capabilities?.let { CapabilityBadge(capabilities = it) }
                    state.app?.let { app ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${app.packageName} • v${app.versionName} (${app.versionCode})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(stringResource(R.string.backup_codec_label), style = MaterialTheme.typography.titleMedium)
                    CodecSelector(
                        selected = state.codec,
                        onSelect = viewModel::setCodec,
                        enabled = !state.running
                    )

                    ToggleRow(
                        label = stringResource(R.string.backup_include_private),
                        checked = state.includePrivateData,
                        enabled = state.canReadPrivateData && !state.running,
                        onChange = viewModel::setIncludePrivate,
                        disabledReason = if (!state.canReadPrivateData)
                            stringResource(R.string.toggle_private_disabled_reason) else null
                    )
                    ToggleRow(
                        label = stringResource(R.string.backup_include_obb),
                        checked = state.includeObb,
                        enabled = !state.running,
                        onChange = viewModel::setIncludeObb
                    )
                    ToggleRow(
                        label = stringResource(R.string.backup_include_external),
                        checked = state.includeExternalData,
                        enabled = !state.running,
                        onChange = viewModel::setIncludeExternal
                    )

                    if (state.willBePartial) {
                        WarningBanner(text = stringResource(R.string.backup_partial_warning))
                    }

                    state.progress?.let { OperationProgressBar(progress = it) }

                    state.error?.let { if (state.app != null) WarningBanner(text = it) }

                    if (state.done) {
                        WarningBanner(
                            text = state.resultMessage ?: "Done",
                            container = MaterialTheme.colorScheme.secondaryContainer
                        )
                        FilledTonalButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_close))
                        }
                    } else {
                        FilledTonalButton(
                            onClick = viewModel::startBackup,
                            enabled = !state.running && state.app != null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.action_start_backup)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    disabledReason: String? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
        if (!enabled && disabledReason != null) {
            Text(
                text = disabledReason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
