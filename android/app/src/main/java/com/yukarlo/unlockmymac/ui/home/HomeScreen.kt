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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yukarlo.unlockmymac.R
import com.yukarlo.unlockmymac.data.AdvertiseMode
import com.yukarlo.unlockmymac.data.AdvertisingState
import com.yukarlo.unlockmymac.data.AuthOutcome
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.ui.components.HeroStatusCard
import com.yukarlo.unlockmymac.ui.components.SectionCard
import com.yukarlo.unlockmymac.ui.components.SettingRow
import com.yukarlo.unlockmymac.ui.components.StatusRow
import com.yukarlo.unlockmymac.ui.components.WarningCard
import com.yukarlo.unlockmymac.ui.components.formatTimestamp
import com.yukarlo.unlockmymac.ui.theme.StatusActiveGreen
import com.yukarlo.unlockmymac.ui.theme.StatusErrorRed
import com.yukarlo.unlockmymac.ui.theme.StatusWarningAmber
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
            val bleGranted = BlePermissions.REQUIRED.all { results[it] != false }
            viewModel.onPermissionResult(bleGranted)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.home_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val settings = state.settings

            // Permission Warning
            if (!state.hasBlePermission) {
                WarningCard(text = stringResource(R.string.home_permissions_body)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    BlePermissions.REQUIRED + BlePermissions.NOTIFICATIONS,
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(stringResource(R.string.home_permissions_grant)) }
                        if (permissionDenied) {
                            OutlinedButton(
                                onClick = { context.startActivity(appSettingsIntent(context.packageName)) },
                                shape = RoundedCornerShape(12.dp),
                            ) { Text(stringResource(R.string.home_permissions_settings)) }
                        }
                    }
                }
            } else if (!state.bluetoothOn) {
                WarningCard(text = stringResource(R.string.home_bluetooth_off))
            }

            // Battery warning
            if (state.hasBlePermission && !state.batteryExempt) {
                WarningCard(text = stringResource(R.string.home_battery_body)) {
                    Text(
                        text = stringResource(R.string.home_battery_samsung),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = viewModel::requestBatteryExemption,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.home_battery_allow))
                    }
                }
            }

            // Pending Approval Request Card
            state.status.pendingApproval?.let { approval ->
                val title =
                    state.pairedMac?.name?.let { stringResource(R.string.home_approval_title_mac, it) }
                        ?: stringResource(R.string.home_approval_title)
                SectionCard(
                    title = title,
                    subtitle = stringResource(R.string.home_approval_body),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { viewModel.resolveApproval(approval.id, true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = StatusActiveGreen,
                                    contentColor = Color.White,
                                ),
                        ) {
                            Text(stringResource(R.string.action_approve), fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.resolveApproval(approval.id, false) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = StatusErrorRed,
                                ),
                        ) {
                            Text(stringResource(R.string.action_deny), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Hero Status Card
            val heroBadge = resolveHeroBadge(state.bluetoothOn, state.status.advertising, settings?.paused == true)
            HeroStatusCard(
                title = state.pairedMac?.name ?: stringResource(R.string.home_not_paired),
                subtitle =
                    resolveHeroSubtitle(
                        state.bluetoothOn,
                        state.status.advertising,
                        state.pairedMac != null,
                        settings?.paused == true,
                    ),
                badgeText = heroBadge.text,
                badgeColor = heroBadge.color,
                icon = if (state.status.connectedCentrals > 0) Icons.Default.BluetoothConnected else Icons.Default.Computer,
            )

            // Settings Controls
            SectionCard(title = "Controls") {
                SettingRow(
                    title = stringResource(R.string.home_service_switch),
                    description = stringResource(R.string.home_service_switch_desc),
                    checked = settings?.serviceEnabled == true,
                    enabled = state.hasBlePermission,
                    icon = Icons.Default.PowerSettingsNew,
                    onCheckedChange = viewModel::setServiceEnabled,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                SettingRow(
                    title = stringResource(R.string.home_pause),
                    description = stringResource(R.string.home_pause_desc),
                    checked = settings?.paused == true,
                    enabled = settings?.serviceEnabled == true,
                    icon = Icons.Default.Bluetooth,
                    onCheckedChange = viewModel::setPaused,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                SettingRow(
                    title = stringResource(R.string.home_require_approval),
                    description = stringResource(R.string.home_require_approval_desc),
                    checked = settings?.requireApproval == true,
                    icon = Icons.Default.Lock,
                    onCheckedChange = viewModel::setRequireApproval,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                SettingRow(
                    title = stringResource(R.string.home_advertise_balanced),
                    description = stringResource(R.string.home_advertise_balanced_desc),
                    checked = settings?.advertiseMode == AdvertiseMode.BALANCED,
                    icon = Icons.Default.Speed,
                    onCheckedChange = viewModel::setBalancedAdvertising,
                )
            }

            // Status Overview
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
                    value = if (state.status.connectedCentrals > 0) "${state.status.connectedCentrals} Connected" else "None",
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

            // Main Actions
            Button(
                onClick = onPair,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    stringResource(
                        if (state.pairedMac == null) {
                            R.string.home_pair_button
                        } else {
                            R.string.home_repair_button
                        },
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }

            // Only useful while the peripheral is meant to be up — resetting a stopped service
            // would do nothing and just look broken.
            if (state.settings?.serviceEnabled == true) {
                OutlinedButton(
                    onClick = viewModel::forceReset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(stringResource(R.string.home_reset_button), fontWeight = FontWeight.Medium)
                }
                Text(
                    text = stringResource(R.string.home_reset_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(
                onClick = onDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(stringResource(R.string.home_diagnostics_button), fontWeight = FontWeight.Medium)
            }
        }
    }
}

private data class BadgeInfo(
    val text: String,
    val color: Color,
)

private fun resolveHeroBadge(
    bluetoothOn: Boolean,
    advertisingState: AdvertisingState,
    isPaused: Boolean,
): BadgeInfo =
    when {
        !bluetoothOn -> BadgeInfo("Bluetooth Off", StatusErrorRed)
        isPaused -> BadgeInfo("Paused", StatusWarningAmber)
        advertisingState == AdvertisingState.ADVERTISING -> BadgeInfo("Broadcasting", StatusActiveGreen)
        advertisingState == AdvertisingState.PAUSED_CONNECTED -> BadgeInfo("Connected", StatusActiveGreen)
        advertisingState == AdvertisingState.FAILED -> BadgeInfo("Error", StatusErrorRed)
        else -> BadgeInfo("Ready", StatusWarningAmber)
    }

private fun resolveHeroSubtitle(
    bluetoothOn: Boolean,
    advertisingState: AdvertisingState,
    isPaired: Boolean,
    isPaused: Boolean,
): String =
    when {
        !bluetoothOn -> "Turn on Bluetooth to auto-unlock this Mac"
        !isPaired -> "Pair with your Mac to get started"
        isPaused -> "Broadcasting is currently paused"
        advertisingState == AdvertisingState.PAUSED_CONNECTED -> "Connected and ready for proximity unlock"
        advertisingState == AdvertisingState.ADVERTISING -> "Broadcasting signal for proximity unlock"
        else -> "Standby"
    }

private fun describeAdvertising(state: AdvertisingState): String =
    when (state) {
        AdvertisingState.STOPPED -> "Stopped"
        AdvertisingState.STARTING -> "Starting"
        AdvertisingState.ADVERTISING -> "Broadcasting"
        AdvertisingState.PAUSED_CONNECTED -> "Connected (broadcasting paused)"
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
