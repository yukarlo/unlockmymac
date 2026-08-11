package com.yukarlo.unlockmymac.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yukarlo.unlockmymac.R
import com.yukarlo.unlockmymac.data.AdvertiseMode
import com.yukarlo.unlockmymac.data.AdvertisingState
import com.yukarlo.unlockmymac.data.AuthOutcome
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.ui.components.SectionCard
import com.yukarlo.unlockmymac.ui.components.SettingRow
import com.yukarlo.unlockmymac.ui.components.StatusRow
import com.yukarlo.unlockmymac.ui.components.WarningCard
import com.yukarlo.unlockmymac.ui.components.formatTimestamp
import com.yukarlo.unlockmymac.util.OnResume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPair: () -> Unit,
    onDiagnostics: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionDenied by viewModel.permissionDenied.collectAsStateWithLifecycle()
    val context = LocalContext.current

    OnResume { viewModel.refreshEnvironment() }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            // Notifications are requested alongside but are not required for BLE to work.
            val bleGranted = BlePermissions.REQUIRED.all { results[it] != false }
            viewModel.onPermissionResult(bleGranted)
        }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
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
            val settings = state.settings

            if (!state.hasBlePermission) {
                WarningCard(text = stringResource(R.string.home_permissions_body)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    BlePermissions.REQUIRED + BlePermissions.NOTIFICATIONS,
                                )
                            },
                        ) { Text(stringResource(R.string.home_permissions_grant)) }
                        if (permissionDenied) {
                            OutlinedButton(
                                onClick = { context.startActivity(appSettingsIntent(context.packageName)) },
                            ) { Text(stringResource(R.string.home_permissions_settings)) }
                        }
                    }
                }
            } else if (!state.bluetoothOn) {
                WarningCard(text = stringResource(R.string.home_bluetooth_off))
            }

            // Shown independently of the Bluetooth warnings: the service can be perfectly
            // configured and still be killed by the OEM battery manager.
            if (state.hasBlePermission && !state.batteryExempt) {
                WarningCard(text = stringResource(R.string.home_battery_body)) {
                    Text(
                        text = stringResource(R.string.home_battery_samsung),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = viewModel::requestBatteryExemption) {
                        Text(stringResource(R.string.home_battery_allow))
                    }
                }
            }

            state.status.pendingApproval?.let { approval ->
                SectionCard(title = stringResource(R.string.home_approval_title)) {
                    Text(
                        text = stringResource(R.string.home_approval_body, approval.challengeTag),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.resolveApproval(approval.id, true) }) {
                            Text(stringResource(R.string.action_approve))
                        }
                        OutlinedButton(onClick = { viewModel.resolveApproval(approval.id, false) }) {
                            Text(stringResource(R.string.action_deny))
                        }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.home_title)) {
                SettingRow(
                    title = stringResource(R.string.home_service_switch),
                    description = stringResource(R.string.home_service_switch_desc),
                    checked = settings?.serviceEnabled == true,
                    enabled = state.hasBlePermission,
                    onCheckedChange = viewModel::setServiceEnabled,
                )
                HorizontalDivider()
                SettingRow(
                    title = stringResource(R.string.home_pause),
                    description = stringResource(R.string.home_pause_desc),
                    checked = settings?.paused == true,
                    enabled = settings?.serviceEnabled == true,
                    onCheckedChange = viewModel::setPaused,
                )
                HorizontalDivider()
                SettingRow(
                    title = stringResource(R.string.home_require_approval),
                    description = stringResource(R.string.home_require_approval_desc),
                    checked = settings?.requireApproval == true,
                    onCheckedChange = viewModel::setRequireApproval,
                )
                HorizontalDivider()
                SettingRow(
                    title = stringResource(R.string.home_advertise_balanced),
                    description = stringResource(R.string.home_advertise_balanced_desc),
                    checked = settings?.advertiseMode == AdvertiseMode.BALANCED,
                    onCheckedChange = viewModel::setBalancedAdvertising,
                )
            }

            SectionCard(title = stringResource(R.string.home_status)) {
                StatusRow(
                    label = stringResource(R.string.home_status_bluetooth),
                    value = if (state.bluetoothOn) "On" else "Off",
                    emphasis = !state.bluetoothOn,
                )
                StatusRow(
                    label = stringResource(R.string.home_status_advertising),
                    value =
                        state.status.advertisingError
                            ?: describeAdvertising(state.status.advertising),
                    emphasis = state.status.advertising == AdvertisingState.FAILED,
                )
                StatusRow(
                    label = stringResource(R.string.home_status_paired),
                    value = state.pairedMac?.name ?: stringResource(R.string.home_not_paired),
                    emphasis = state.pairedMac == null,
                )
                StatusRow(
                    label = stringResource(R.string.home_status_connected),
                    value = state.status.connectedCentrals.toString(),
                )
                StatusRow(
                    label = stringResource(R.string.home_status_last_challenge),
                    value =
                        formatTimestamp(
                            state.status.lastChallengeAtMs,
                            stringResource(R.string.home_never),
                        ),
                )
                StatusRow(
                    label = stringResource(R.string.home_status_last_auth),
                    value =
                        state.status.lastAuth?.let { "${describeOutcome(it.outcome)} · ${it.challengeTag}" }
                            ?: stringResource(R.string.home_never),
                    emphasis =
                        state.status.lastAuth?.outcome != null &&
                            state.status.lastAuth?.outcome != AuthOutcome.SUCCESS,
                )
            }

            Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (state.pairedMac == null) {
                            R.string.home_pair_button
                        } else {
                            R.string.home_repair_button
                        },
                    ),
                )
            }
            TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_diagnostics_button))
            }
        }
    }
}

private fun describeAdvertising(state: AdvertisingState): String =
    when (state) {
        AdvertisingState.STOPPED -> "Stopped"
        AdvertisingState.STARTING -> "Starting"
        AdvertisingState.ADVERTISING -> "Advertising"
        AdvertisingState.PAUSED_CONNECTED -> "Connected (advertising paused)"
        AdvertisingState.FAILED -> "Failed"
        AdvertisingState.BLUETOOTH_OFF -> "Bluetooth off"
        AdvertisingState.NO_PERMISSION -> "No permission"
    }

private fun describeOutcome(outcome: AuthOutcome): String =
    when (outcome) {
        AuthOutcome.SUCCESS -> "Signed"
        AuthOutcome.REJECTED -> "Rejected"
        AuthOutcome.DENIED -> "Denied"
        AuthOutcome.ERROR -> "Error"
    }

private fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
