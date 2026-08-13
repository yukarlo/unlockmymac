package com.yukarlo.unlockmymac.wear

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
            contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
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
            } else if (!hasNotificationPermission) {
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

            // Settings Card Container matching screenshot
            item {
                val serviceEnabled = settings?.serviceEnabled == true
                val requireApproval = settings?.requireApproval == true
                val fastDiscovery = settings?.advertiseMode == AdvertiseMode.BALANCED

                WearSettingCard {
                    // Row 1: Discoverable by Mac
                    WearSettingRow(
                        title = context.getString(R.string.home_service_switch),
                        description = if (serviceEnabled) "Broadcast signal to unlock Mac" else "Disabled",
                        checked = serviceEnabled,
                        onCheckedChange = { newValue ->
                            scope.launch {
                                container.settings.setServiceEnabled(newValue)
                                if (newValue) BleUnlockService.start(context) else BleUnlockService.stop(context)
                            }
                        },
                        showDividerBelow = true,
                    )

                    // Row 2: Approve every request
                    WearSettingRow(
                        title = context.getString(R.string.home_require_approval),
                        description = if (requireApproval) "Require tap on watch" else "Auto-unlock without asking",
                        checked = requireApproval,
                        onCheckedChange = { newValue ->
                            scope.launch { container.settings.setRequireApproval(newValue) }
                        },
                        showDividerBelow = true,
                    )

                    // Row 3: Fast discovery mode
                    WearSettingRow(
                        title = context.getString(R.string.home_fast_discovery),
                        description = if (fastDiscovery) "Faster detection, uses more battery" else "Standard power mode",
                        checked = fastDiscovery,
                        onCheckedChange = { newValue ->
                            scope.launch {
                                container.settings.setAdvertiseMode(
                                    if (newValue) AdvertiseMode.BALANCED else AdvertiseMode.LOW_POWER,
                                )
                            }
                        },
                        showDividerBelow = false,
                    )
                }
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

            // Bottom padding spacer so bottom card item can scroll fully into view
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun WearSettingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF242428)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun WearSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDividerBelow: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onCheckedChange(!checked) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.body1.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.caption2.copy(
                        color = Color(0xFF8AB4F8),
                        fontWeight = FontWeight.Normal,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .height(30.dp)
                    .width(1.dp)
                    .background(Color(0x33FFFFFF)),
            )

            Switch(
                checked = checked,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        if (showDividerBelow) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 14.dp)
                    .background(Color(0x22FFFFFF)),
            )
        }
    }
}
