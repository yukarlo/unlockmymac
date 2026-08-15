package com.yukarlo.unlockmymac.ui.home

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.data.AdvertiseMode
import com.yukarlo.unlockmymac.data.AppSettings
import com.yukarlo.unlockmymac.data.BleStatus
import com.yukarlo.unlockmymac.data.PairedMac
import com.yukarlo.unlockmymac.data.Timeouts
import com.yukarlo.unlockmymac.permissions.BatteryOptimization
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.service.BleUnlockService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeUiState(
    val settings: AppSettings?,
    val status: BleStatus,
    val pairedMac: PairedMac?,
    val hasBlePermission: Boolean,
    val bluetoothOn: Boolean,
    /** False means an OEM battery manager may kill the service without warning. */
    val batteryExempt: Boolean,
    val connectedSmartwatches: List<String> = emptyList(),
)

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application
    private val container = application.container

    private val permissionGranted = MutableStateFlow(BlePermissions.hasBleAccess(application))
    private val bluetoothOn = MutableStateFlow(isBluetoothOn())
    private val batteryExempt = MutableStateFlow(BatteryOptimization.isExempt(application))
    private val connectedSmartwatches = MutableStateFlow<List<String>>(emptyList())

    /** Device-level signals, folded together because `combine` only types up to five flows. */
    private val environment =
        combine(permissionGranted, bluetoothOn, batteryExempt, connectedSmartwatches) { granted, btOn, exempt, watches ->
            Environment(granted, btOn, exempt, watches)
        }

    val uiState: StateFlow<HomeUiState> =
        combine(
            container.settings.settings,
            container.status.status,
            container.pairing.pairedMac,
            environment,
        ) { settings, status, paired, env ->
            HomeUiState(
                settings = settings,
                status = status,
                pairedMac = paired,
                hasBlePermission = env.hasBlePermission,
                bluetoothOn = env.bluetoothOn,
                batteryExempt = env.batteryExempt,
                connectedSmartwatches = env.connectedSmartwatches,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue =
                HomeUiState(
                    settings = null,
                    status = BleStatus(),
                    pairedMac = null,
                    hasBlePermission = permissionGranted.value,
                    bluetoothOn = bluetoothOn.value,
                    batteryExempt = batteryExempt.value,
                    connectedSmartwatches = emptyList(),
                ),
        )

    private class Environment(
        val hasBlePermission: Boolean,
        val bluetoothOn: Boolean,
        val batteryExempt: Boolean,
        val connectedSmartwatches: List<String>,
    )

    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied.asStateFlow()

    /** Re-reads permission and adapter state; call from ON_RESUME and after a permission result. */
    fun refreshEnvironment() {
        permissionGranted.value = BlePermissions.hasBleAccess(app)
        bluetoothOn.value = isBluetoothOn()
        batteryExempt.value = BatteryOptimization.isExempt(app)
        viewModelScope.launch {
            connectedSmartwatches.value = fetchSmartwatches()
        }
        reconcileServiceState()
    }

    private suspend fun fetchSmartwatches(): List<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes)
                nodes.map { it.displayName }
            }.getOrDefault(emptyList())
        }

    /** Opens the system Doze-exemption prompt. Only the user can actually grant it. */
    fun requestBatteryExemption() {
        runCatching { app.startActivity(BatteryOptimization.requestExemptionIntent(app.packageName)) }
            .onFailure {
                // Some OEM builds hide the direct prompt; fall back to the app settings page.
                runCatching { app.startActivity(BatteryOptimization.appSettingsIntent(app.packageName)) }
            }
    }

    /**
     * Restarts the foreground service when the saved switch says it should be running but the
     * process is not.
     *
     * `serviceEnabled` lives in DataStore and survives the process; `serviceRunning` is
     * in-memory and does not. After an app reinstall, a force stop, or a reboot the switch
     * would otherwise read ON while nothing advertises — the switch lying about the radio is
     * exactly the failure mode this app must never have.
     */
    private fun reconcileServiceState() {
        if (!BlePermissions.hasBleAccess(app)) return
        viewModelScope.launch {
            val current = container.settings.settings.first()
            if (current.serviceEnabled && !container.status.status.value.serviceRunning) {
                // Reaching here means the switch says ON but nothing was running — the service
                // died while the app was closed. A warning, not a note: it is the only trace of
                // a kill that happened with no UI open to log it.
                container.eventLog.warn("Service was not running despite being enabled; restarting")
                BleUnlockService.start(app)
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        refreshEnvironment()
        _permissionDenied.value = !granted
        if (granted) setServiceEnabled(true)
    }

    fun setServiceEnabled(enabled: Boolean) {
        if (enabled && !BlePermissions.hasBleAccess(app)) {
            // The service cannot enter the foreground as connectedDevice without these, so
            // never flip the setting on a promise the system will refuse to keep.
            _permissionDenied.value = true
            return
        }
        viewModelScope.launch {
            // Log the intent, not just the effect. Without this the only trace is the service
            // dying, which looks identical to being killed.
            container.eventLog.info(
                if (enabled) "Discoverable turned ON by user" else "Discoverable turned OFF by user",
            )
            container.settings.setServiceEnabled(enabled)
            if (enabled) BleUnlockService.start(app) else BleUnlockService.stop(app)
        }
    }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch {
            container.eventLog.info(if (paused) "Paused by user" else "Resumed by user")
            container.settings.setPaused(paused)
        }
    }

    fun setRequireApproval(required: Boolean) {
        viewModelScope.launch { container.settings.setRequireApproval(required) }
    }





    fun setBalancedAdvertising(balanced: Boolean) {
        viewModelScope.launch {
            container.settings.setAdvertiseMode(
                if (balanced) AdvertiseMode.BALANCED else AdvertiseMode.LOW_POWER,
            )
        }
    }

    fun resolveApproval(
        id: Long,
        approved: Boolean,
    ) {
        BleUnlockService.resolveApproval(app, id, approved)
    }

    /**
     * Tears the BLE stack down and back up, and hangs up on any connected Mac.
     *
     * The escape hatch for the state where both apps look healthy but no challenge ever
     * arrives, because a link is half-open below the app layer. Dropping the connection is what
     * reaches the Mac — it sees the disconnect and starts over on the next advertisement.
     */
    fun forceReset() {
        BleUnlockService.forceReset(app)
        // The button was silent before, so it looked broken and got pressed repeatedly.
        viewModelScope.launch {
            _resetFeedback.value = true
            delay(RESET_FEEDBACK_MS)
            _resetFeedback.value = false
        }
    }

    private val _resetFeedback = MutableStateFlow(false)

    /** True briefly after a reset, so the button can confirm it did something. */
    val resetFeedback: StateFlow<Boolean> = _resetFeedback.asStateFlow()

    /**
     * Seconds until the Mac will ask again after a denial, or null when it is free to ask.
     *
     * Counted locally: the backoff lives on the Mac and the phone has no way to observe it, so
     * this mirrors `Timeouts.DENIAL_BACKOFF_MS`. Without it the two minutes of deliberate
     * silence after a denial is indistinguishable from a failure — which is exactly how it read.
     *
     * Derived rather than driven from `init`: a constructor that starts a coroutine touching a
     * property declared further down the class reads that property before its initialiser runs,
     * which threw an NPE on every launch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val denialSecondsLeft: StateFlow<Int?> =
        container.status.status
            .map { it.deniedAtMs }
            .distinctUntilChanged()
            .flatMapLatest { deniedAt ->
                if (deniedAt == null) {
                    flowOf<Int?>(null)
                } else {
                    // Ticks only while a denial is live and the screen is watching.
                    flow<Int?> {
                        while (true) {
                            val remaining = Timeouts.DENIAL_BACKOFF_MS - (System.currentTimeMillis() - deniedAt)
                            if (remaining <= 0) {
                                emit(null)
                                break
                            }
                            emit(((remaining + 999) / 1000).toInt())
                            delay(1_000)
                        }
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    private companion object {
        /** How long the reset button shows its confirmation. */
        const val RESET_FEEDBACK_MS = 4_000L
    }

    private fun isBluetoothOn(): Boolean = app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}
