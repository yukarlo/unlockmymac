package com.yukarlo.unlockmymac.ui.pairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yukarlo.unlockmymac.ble.PairingCodec
import com.yukarlo.unlockmymac.ble.ParseResult
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.data.AdvertisingState
import com.yukarlo.unlockmymac.pairing.PairingWindow
import com.yukarlo.unlockmymac.permissions.BlePermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PairingStep {
    data object Scanning : PairingStep

    class WaitingForMac(
        val macName: String,
        val secondsLeft: Int,
    ) : PairingStep

    class Paired(
        val macName: String,
        val fingerprint: String,
    ) : PairingStep
}

data class PairingUiState(
    val step: PairingStep = PairingStep.Scanning,
    val hasCameraPermission: Boolean = false,
    val error: PairingError? = null,
    val alreadyPairedWith: String? = null,
    /** Pairing happens over GATT, so it only works while the peripheral is actually up. */
    val peripheralReady: Boolean = false,
)

enum class PairingError { INVALID_QR, QR_EXPIRED, WINDOW_CLOSED }

class PairingViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application
    private val container = application.container

    private val _uiState =
        MutableStateFlow(
            PairingUiState(hasCameraPermission = BlePermissions.hasCamera(application)),
        )
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    /** Guards against the analyzer firing repeatedly while the same QR stays in frame. */
    @Volatile
    private var scanConsumed = false

    init {
        viewModelScope.launch {
            container.pairing.pairedMac.collect { paired ->
                _uiState.update { it.copy(alreadyPairedWith = paired?.name) }
            }
        }
        viewModelScope.launch {
            container.status.status.collect { status ->
                _uiState.update {
                    it.copy(
                        peripheralReady =
                            status.serviceRunning &&
                                status.advertising == AdvertisingState.ADVERTISING ||
                                // The Mac's own pairing connection pauses advertising; that is
                                // the peripheral working, not a reason to warn the user.
                                status.advertising == AdvertisingState.PAUSED_CONNECTED,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.pairingCoordinator.window.collect { window ->
                when (window) {
                    is PairingWindow.Claimed -> {
                        val fingerprint =
                            runCatching { container.signer.identity().fingerprint }
                                .getOrDefault("")
                        _uiState.update {
                            it.copy(step = PairingStep.Paired(window.invite.macName, fingerprint), error = null)
                        }
                    }

                    is PairingWindow.Open -> {
                        Unit
                    }

                    // Countdown is driven by the ticker below.
                    PairingWindow.Closed -> {
                        if (_uiState.value.step is PairingStep.WaitingForMac) {
                            scanConsumed = false
                            _uiState.update {
                                it.copy(step = PairingStep.Scanning, error = PairingError.WINDOW_CLOSED)
                            }
                        }
                    }
                }
            }
        }
        startCountdown()
    }

    fun onCameraPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    fun refreshPermissions() {
        _uiState.update { it.copy(hasCameraPermission = BlePermissions.hasCamera(app)) }
    }

    fun onQrScanned(text: String) {
        if (scanConsumed) return
        when (val parsed = PairingCodec.parseInvite(text)) {
            is ParseResult.Invalid -> {
                // Do not latch on a bad code: the user may be pointing at the wrong thing.
                _uiState.update { it.copy(error = PairingError.INVALID_QR) }
            }

            is ParseResult.Valid -> {
                val now = System.currentTimeMillis()
                val expiresAt = container.pairingCoordinator.open(parsed.value, now)
                if (expiresAt == null) {
                    _uiState.update { it.copy(error = PairingError.QR_EXPIRED) }
                    return
                }
                scanConsumed = true
                container.status.setPairingWindow(expiresAt)
                container.eventLog.info("Pairing window opened for ${parsed.value.macName}")
                _uiState.update {
                    it.copy(
                        step = PairingStep.WaitingForMac(parsed.value.macName, secondsLeft(expiresAt, now)),
                        error = null,
                    )
                }
            }
        }
    }

    fun cancel() {
        container.pairingCoordinator.close()
        container.status.setPairingWindow(null)
        scanConsumed = false
        _uiState.update { it.copy(step = PairingStep.Scanning, error = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                container.pairingCoordinator.sweep(now)
                val window = container.pairingCoordinator.window.value
                if (window is PairingWindow.Open) {
                    val remaining = secondsLeft(window.expiresAtMs, now)
                    _uiState.update {
                        val step = it.step
                        if (step is PairingStep.WaitingForMac) {
                            it.copy(step = PairingStep.WaitingForMac(step.macName, remaining))
                        } else {
                            it
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun secondsLeft(
        expiresAtMs: Long,
        nowMs: Long,
    ): Int = ((expiresAtMs - nowMs).coerceAtLeast(0L) / 1000L).toInt()

    override fun onCleared() {
        // Leaving the screen must not leave a pairing window open behind the user's back.
        if (container.pairingCoordinator.window.value is PairingWindow.Open) {
            container.pairingCoordinator.close()
            container.status.setPairingWindow(null)
        }
    }
}
