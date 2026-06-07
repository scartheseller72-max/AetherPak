package com.zorg.aetherpak.ui.screens.restore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zorg.aetherpak.R
import com.zorg.aetherpak.ui.AetherViewModelFactory
import com.zorg.aetherpak.ui.components.OperationProgressBar
import com.zorg.aetherpak.ui.components.WarningBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    viewModel: RestoreViewModel = viewModel(factory = AetherViewModelFactory())
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locals by viewModel.localBackups.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.selectExternal(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_restore)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.restore_external_title), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.action_pick_file)) }

            Text(stringResource(R.string.restore_local_title), style = MaterialTheme.typography.titleMedium)
            if (locals.isEmpty()) {
                Text(
                    stringResource(R.string.restore_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                locals.forEach { record ->
                    Card(
                        onClick = { viewModel.selectLocal(record) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(record.appLabel, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${record.packageName} • v${record.versionName}" +
                                    if (record.isPartial) " • PARTIAL" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "View details",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                                    .clickableOpen { onOpenDetail(record.id) }
                            )
                        }
                    }
                }
            }

            if (state.loading) CircularProgressIndicator()

            state.manifest?.let { manifest ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.restore_manifest_preview), style = MaterialTheme.typography.titleMedium)
                        Text("${manifest.appLabel} (${manifest.packageName})", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "v${manifest.versionName} • codec ${manifest.codec} • " +
                                "${manifest.entries.size} entries",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        manifest.components.forEach { c ->
                            Text(
                                "• ${c.type.name}: ${if (c.included) "${c.fileCount} files" else "excluded"}" +
                                    (c.skippedReason?.let { " ($it)" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (state.isPartial) {
                WarningBanner(text = stringResource(R.string.restore_partial_warning))
            }

            state.progress?.let { OperationProgressBar(progress = it) }
            state.error?.let { WarningBanner(text = it) }

            if (state.done) {
                WarningBanner(
                    text = state.resultMessage ?: "Done",
                    container = MaterialTheme.colorScheme.secondaryContainer
                )
                state.warnings.forEach { WarningBanner(text = it) }
            }

            FilledTonalButton(
                onClick = viewModel::startRestore,
                enabled = !state.running && state.selectedArchivePath != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.action_start_restore)) }
        }
    }
}

private fun Modifier.clickableOpen(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
