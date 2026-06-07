package com.zorg.aetherpak.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zorg.aetherpak.R
import com.zorg.aetherpak.ui.AetherViewModelFactory
import com.zorg.aetherpak.ui.components.AppListItem
import com.zorg.aetherpak.ui.components.CapabilityBadge
import com.zorg.aetherpak.ui.components.WarningBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAppClick: (String) -> Unit,
    onRestore: () -> Unit,
    onSettings: () -> Unit,
    onAccessSetup: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = AetherViewModelFactory())
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringRes(R.string.title_home)) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Filled.Restore, contentDescription = "Restore")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.capabilities?.let { CapabilityBadge(capabilities = it) }

            if (!state.hasAccess) {
                WarningBanner(text = stringRes(R.string.cap_none_desc))
                androidx.compose.material3.FilledTonalButton(
                    onClick = onAccessSetup,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringRes(R.string.title_access_setup)) }
            }

            if (state.shizukuOnly) {
                WarningBanner(text = stringRes(R.string.banner_shizuku_only))
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringRes(R.string.home_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringRes(R.string.home_show_system), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.showSystem, onCheckedChange = viewModel::onToggleSystem)
            }

            when {
                state.loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }

                state.error != null -> WarningBanner(text = state.error ?: "")

                state.filtered.isEmpty() -> Text(
                    stringRes(R.string.home_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.filtered, key = { it.packageName }) { app ->
                        AppListItem(app = app, onClick = { onAppClick(app.packageName) })
                    }
                }
            }
        }
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
