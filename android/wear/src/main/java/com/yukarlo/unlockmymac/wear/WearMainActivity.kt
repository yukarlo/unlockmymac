package com.yukarlo.unlockmymac.wear

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Chip
import com.yukarlo.unlockmymac.container
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
    var enrolMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            hasPermission = granted.values.all { it }
        }

    val bluetoothOn =
        remember {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text =
                pairedMac?.let { stringOf(context, R.string.home_paired, it.name) }
                    ?: context.getString(R.string.home_not_paired),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.title3,
        )

        if (!hasPermission) {
            Text(
                text = context.getString(R.string.home_permission_needed),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
            )
            CompactChip(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ),
                    )
                },
                label = { Text(context.getString(R.string.home_grant)) },
            )
            return@Column
        }

        if (!bluetoothOn) {
            Text(
                text = context.getString(R.string.home_bluetooth_off),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
            )
        }

        val enabled = settings?.serviceEnabled == true
        Chip(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    container.settings.setServiceEnabled(!enabled)
                    if (!enabled) BleUnlockService.start(context) else BleUnlockService.stop(context)
                }
            },
            label = { Text(context.getString(R.string.home_service_switch)) },
            secondaryLabel = { Text(if (enabled) "On" else "Off") },
        )

        // Defaults to off, so without this the watch unlocks silently while the phone asks —
        // a difference in what it takes to open the Mac that the wearer could not see or change.
        val requireApproval = settings?.requireApproval == true
        Chip(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch { container.settings.setRequireApproval(!requireApproval) }
            },
            label = { Text(context.getString(R.string.home_require_approval)) },
            secondaryLabel = { Text(if (requireApproval) "On" else "Off") },
        )

        Text(
            text = if (status.advertising.name == "ADVERTISING") "Broadcasting" else status.advertising.name.lowercase(),
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
        )

        // Enrolment: the watch cannot scan the Mac's QR, so it asks the phone — already trusted by
        // the Mac — to vouch for the key it just generated.
        if (pairedMac == null) {
            Text(
                text = context.getString(R.string.home_enrol_hint),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
            )
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
            enrolMessage?.let {
                Text(text = it, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2)
            }
        }
    }
}

private fun stringOf(
    context: android.content.Context,
    resId: Int,
    arg: String,
): String = context.getString(resId, arg)
