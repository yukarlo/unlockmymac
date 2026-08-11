package com.yukarlo.unlockmymac.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yukarlo.unlockmymac.R
import com.yukarlo.unlockmymac.crypto.KeySecurityLevel
import com.yukarlo.unlockmymac.data.EventLevel
import com.yukarlo.unlockmymac.data.LogEvent
import com.yukarlo.unlockmymac.ui.components.SectionCard
import com.yukarlo.unlockmymac.ui.components.StatusRow
import com.yukarlo.unlockmymac.ui.components.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val showUnpairPrompt by viewModel.unpairPrompt.collectAsStateWithLifecycle()

    if (showUnpairPrompt) {
        val macName = state.pairedMac?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { viewModel.promptUnpair(false) },
            title = { Text(stringResource(R.string.diagnostics_unpair)) },
            text = { Text(stringResource(R.string.diagnostics_unpair_confirm, macName)) },
            confirmButton = {
                TextButton(onClick = viewModel::unpair) {
                    Text(stringResource(R.string.diagnostics_unpair_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.promptUnpair(false) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = stringResource(R.string.diagnostics_key)) {
                Text(
                    text = stringResource(R.string.diagnostics_key_fingerprint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.keyInfo?.fingerprint ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                StatusRow(
                    label = stringResource(R.string.diagnostics_key_storage),
                    value = describeSecurityLevel(state.keyInfo?.securityLevel),
                    emphasis = state.keyInfo?.securityLevel == KeySecurityLevel.SOFTWARE,
                )
                StatusRow(
                    label = stringResource(R.string.diagnostics_device_id),
                    value = state.deviceId.take(8).ifEmpty { "—" },
                )
                if (state.pairedMac != null) {
                    OutlinedButton(
                        onClick = { viewModel.promptUnpair(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.diagnostics_unpair)) }
                }
            }

            SectionCard(title = stringResource(R.string.diagnostics_events)) {
                Text(
                    text = stringResource(R.string.diagnostics_no_secrets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.events.isEmpty()) {
                    Text(
                        text = stringResource(R.string.diagnostics_events_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // The log is capped at 200 entries, so rendering it in the parent scroll
                    // container is cheaper than nesting a LazyColumn.
                    state.events.forEach { event ->
                        EventRow(event)
                        HorizontalDivider()
                    }
                    OutlinedButton(
                        onClick = viewModel::clearLog,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.diagnostics_clear)) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: LogEvent) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = formatTimestamp(event.atMs, "—"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = event.message,
            style = MaterialTheme.typography.bodyMedium,
            color =
                when (event.level) {
                    EventLevel.ERROR -> MaterialTheme.colorScheme.error
                    EventLevel.WARN -> MaterialTheme.colorScheme.tertiary
                    EventLevel.INFO -> MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

private fun describeSecurityLevel(level: KeySecurityLevel?): String =
    when (level) {
        KeySecurityLevel.STRONGBOX -> "StrongBox"
        KeySecurityLevel.TRUSTED_ENVIRONMENT -> "Hardware-backed"
        KeySecurityLevel.SOFTWARE -> "Software only"
        KeySecurityLevel.UNKNOWN, null -> "Unknown"
    }
