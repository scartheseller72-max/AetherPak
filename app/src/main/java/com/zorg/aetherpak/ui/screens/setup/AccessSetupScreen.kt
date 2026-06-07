package com.zorg.aetherpak.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.ui.AetherViewModelFactory
import com.zorg.aetherpak.ui.components.WarningBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessSetupScreen(
    onBack: () -> Unit,
    viewModel: AccessSetupViewModel = viewModel(factory = AetherViewModelFactory())
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_access_setup)) },
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
            OutlinedButton(
                onClick = { viewModel.detect() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading
            ) { Text(stringResource(R.string.access_detect)) }

            if (state.loading) CircularProgressIndicator()
            state.error?.let { WarningBanner(text = it) }

            AccessCard(
                mode = AccessMode.ROOT,
                title = stringResource(R.string.cap_root_title),
                desc = stringResource(R.string.cap_root_desc),
                available = state.available.contains(AccessMode.ROOT),
                granted = state.grantedMode == AccessMode.ROOT,
                requesting = state.requesting,
                onRequest = { viewModel.request(AccessMode.ROOT) }
            )

            AccessCard(
                mode = AccessMode.SHIZUKU,
                title = stringResource(R.string.cap_shizuku_title),
                desc = stringResource(R.string.cap_shizuku_desc),
                available = state.available.contains(AccessMode.SHIZUKU),
                granted = state.grantedMode == AccessMode.SHIZUKU,
                requesting = state.requesting,
                onRequest = { viewModel.request(AccessMode.SHIZUKU) }
            )

            if (state.grantedMode != null) {
                FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun AccessCard(
    mode: AccessMode,
    title: String,
    desc: String,
    available: Boolean,
    granted: Boolean,
    requesting: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                granted -> Text(
                    "✓ ${stringResource(R.string.access_granted)} ($mode)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                available -> FilledTonalButton(
                    onClick = onRequest,
                    enabled = !requesting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.access_grant)) }
                else -> Text(
                    stringResource(R.string.access_not_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
