package com.yukarlo.unlockmymac.wear

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.data.AdvertiseMode
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.service.BleUnlockService
import kotlinx.coroutines.launch

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WearHome() } }
    }
}

@Composable
private fun WearHome() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()

    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val status by container.status.status.collectAsStateWithLifecycle()
    val pairedMac by container.pairing.pairedMac.collectAsStateWithLifecycle(initialValue = null)

    var hasPermission by remember { mutableStateOf(BlePermissions.hasBleAccess(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var enrolMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            hasPermission = BlePermissions.hasBleAccess(context)
            hasNotificationPermission =
                granted[Manifest.permission.POST_NOTIFICATIONS]
                    ?: (
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                        )
        }

    val bluetoothOn =
        remember {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }

    val listState = rememberScalingLazyListState()

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header / Title & Paired Mac Name
            item {
                ListHeader {
                    Text(
                        text = pairedMac?.name ?: context.getString(R.string.home_not_paired),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Status Indicator Subtitle
            item {
                val statusText =
                    when {
                        !bluetoothOn -> context.getString(R.string.home_bluetooth_off)
                        status.advertising.name == "ADVERTISING" -> "Broadcasting"
                        status.connectedCentrals > 0 -> "Connected"
                        else -> status.advertising.name.lowercase()
                    }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Permission Warnings
            if (!hasPermission) {
                item {
                    Text(
                        text = context.getString(R.string.home_permission_needed),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption2,
                    )
                }
                item {
                    CompactChip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_ADVERTISE,
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ),
                            )
                        },
                        label = { Text(context.getString(R.string.home_grant)) },
                    )
                }
                return@ScalingLazyColumn
            }

            if (!hasNotificationPermission) {
                item {
                    Text(
                        text = context.getString(R.string.home_notifications_needed),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption2,
                    )
                }
                item {
                    CompactChip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        },
                        label = { Text(context.getString(R.string.home_grant)) },
                    )
                }
            }

            // Toggle 1: Discoverable by Mac
            item {
                val enabled = settings?.serviceEnabled == true
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = enabled,
                    onCheckedChange = { newValue ->
                        scope.launch {
                            container.settings.setServiceEnabled(newValue)
                            if (newValue) BleUnlockService.start(context) else BleUnlockService.stop(context)
                        }
                    },
                    label = {
                        Text(
                            text = context.getString(R.string.home_service_switch),
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )
                    },
                    secondaryLabel = {
                        Text(if (enabled) "Active" else "Off")
                    },
                    toggleControl = {
                        Switch(checked = enabled)
                    },
                )
            }

            // Toggle 2: Approve every request
            item {
                val requireApproval = settings?.requireApproval == true
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = requireApproval,
                    onCheckedChange = { newValue ->
                        scope.launch { container.settings.setRequireApproval(newValue) }
                    },
                    label = {
                        Text(
                            text = context.getString(R.string.home_require_approval),
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )
                    },
                    secondaryLabel = {
                        Text(if (requireApproval) "Require tap" else "Auto-unlock")
                    },
                    toggleControl = {
                        Switch(checked = requireApproval)
                    },
                )
            }

            // Toggle 3: Fast discovery mode
            item {
                val fastDiscovery = settings?.advertiseMode == AdvertiseMode.BALANCED
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = fastDiscovery,
                    onCheckedChange = { newValue ->
                        scope.launch {
                            container.settings.setAdvertiseMode(
                                if (newValue) AdvertiseMode.BALANCED else AdvertiseMode.LOW_POWER,
                            )
                        }
                    },
                    label = {
                        Text(
                            text = context.getString(R.string.home_fast_discovery),
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )
                    },
                    secondaryLabel = {
                        Text(if (fastDiscovery) "Faster" else "Low power")
                    },
                    toggleControl = {
                        Switch(checked = fastDiscovery)
                    },
                )
            }

            // Enrolment Card if not paired
            if (pairedMac == null) {
                item {
                    Text(
                        text = context.getString(R.string.home_enrol_hint),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item {
                    CompactChip(
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(context.getString(R.string.home_enrol_send)) },
                        onClick = {
                            scope.launch {
                                enrolMessage =
                                    when (val result = WearEnrolmentSender.sendPublicKey(context)) {
                                        WearEnrolmentSender.Result.Sent -> {
                                            context.getString(R.string.home_enrol_offer_sent)
                                        }

                                        WearEnrolmentSender.Result.NoPhoneReachable -> {
                                            "Phone not reachable"
                                        }

                                        is WearEnrolmentSender.Result.Failed -> {
                                            result.reason
                                        }
                                    }
                            }
                        },
                    )
                }
                enrolMessage?.let { msg ->
                    item {
                        Text(
                            text = msg,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.caption2,
                        )
                    }
                }
            }
        }
    }
}
