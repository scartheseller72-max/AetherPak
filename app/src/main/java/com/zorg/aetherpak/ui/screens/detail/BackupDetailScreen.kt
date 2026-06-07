package com.zorg.aetherpak.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.zorg.aetherpak.ui.components.WarningBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDetailScreen(
    backupId: Long,
    onBack: () -> Unit,
    viewModel: BackupDetailViewModel = viewModel(factory = AetherViewModelFactory(backupId = backupId))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_backup_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
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
            state.record?.let { record ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(record.appLabel, style = MaterialTheme.typography.titleLarge)
                        DetailRow("Package", record.packageName)
                        DetailRow("Version", "${record.versionName} (${record.versionCode})")
                        DetailRow("Codec", record.codec)
                        DetailRow("Access mode", record.accessMode)
                        DetailRow("Size", "${record.archiveSizeBytes / 1024} KB")
                        DetailRow("Partial", if (record.isPartial) "Yes" else "No")
                        DetailRow("Path", record.archivePath)
                    }
                }
            }

            if (state.loading) CircularProgressIndicator()
            state.error?.let { WarningBanner(text = it) }

            state.manifest?.let { manifest ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Components", style = MaterialTheme.typography.titleMedium)
                        manifest.components.forEach { c ->
                            Text(
                                "• ${c.type.name}: " +
                                    (if (c.included) "${c.fileCount} files, ${c.totalBytes / 1024} KB"
                                    else "excluded") +
                                    (c.skippedReason?.let { " ($it)" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        manifest.notes?.let {
                            Text("Notes: $it", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
