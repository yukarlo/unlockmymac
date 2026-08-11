package com.yukarlo.unlockmymac.ui.pairing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yukarlo.unlockmymac.R
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.ui.components.SectionCard
import com.yukarlo.unlockmymac.ui.components.WarningCard
import com.yukarlo.unlockmymac.util.OnResume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onBack: () -> Unit,
    viewModel: PairingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    OnResume { viewModel.refreshPermissions() }

    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> viewModel.onCameraPermissionResult(granted) }

    LaunchedEffect(state.hasCameraPermission) {
        if (!state.hasCameraPermission) cameraLauncher.launch(BlePermissions.CAMERA.first())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_title)) },
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
            state.error?.let { error ->
                WarningCard(
                    text =
                        stringResource(
                            when (error) {
                                PairingError.INVALID_QR -> R.string.pairing_invalid_qr
                                PairingError.QR_EXPIRED -> R.string.pairing_qr_expired
                                PairingError.WINDOW_CLOSED -> R.string.pairing_expired
                            },
                        ),
                ) {
                    OutlinedButton(onClick = viewModel::dismissError) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }

            if (!state.peripheralReady && state.step is PairingStep.Scanning) {
                WarningCard(text = stringResource(R.string.pairing_peripheral_off))
            }

            state.alreadyPairedWith?.let { existing ->
                if (state.step !is PairingStep.Paired) {
                    Text(
                        text = stringResource(R.string.pairing_replace_warning, existing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (val step = state.step) {
                PairingStep.Scanning -> {
                    Text(
                        text = stringResource(R.string.pairing_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.hasCameraPermission) {
                        QrScannerView(
                            onQrCode = viewModel::onQrScanned,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(3f / 4f)
                                    .clip(RoundedCornerShape(16.dp)),
                        )
                    } else {
                        WarningCard(text = stringResource(R.string.pairing_camera_needed)) {
                            Button(onClick = { cameraLauncher.launch(BlePermissions.CAMERA.first()) }) {
                                Text(stringResource(R.string.pairing_camera_grant))
                            }
                        }
                    }
                }

                is PairingStep.WaitingForMac -> {
                    SectionCard(title = stringResource(R.string.pairing_title)) {
                        Text(
                            text =
                                stringResource(
                                    R.string.pairing_window_open,
                                    step.macName,
                                    step.secondsLeft,
                                ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.pairing_window_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CircularProgressIndicator()
                        OutlinedButton(onClick = viewModel::cancel) {
                            Text(stringResource(R.string.pairing_cancel))
                        }
                    }
                }

                is PairingStep.Paired -> {
                    SectionCard(title = stringResource(R.string.pairing_success, step.macName)) {
                        Text(
                            text = stringResource(R.string.pairing_success_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = step.fingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.pairing_done))
                        }
                    }
                }
            }
        }
    }
}
