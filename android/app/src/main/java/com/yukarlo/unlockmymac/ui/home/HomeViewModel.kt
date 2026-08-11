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
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.service.BleUnlockService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeUiState(
    val settings: AppSettings?,
    val status: BleStatus,
    val pairedMac: PairedMac?,
    val hasBlePermission: Boolean,
    val bluetoothOn: Boolean,
)

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application
    private val container = application.container

    private val permissionGranted = MutableStateFlow(BlePermissions.hasBleAccess(application))
    private val bluetoothOn = MutableStateFlow(isBluetoothOn())

    val uiState: StateFlow<HomeUiState> =
        combine(
            container.settings.settings,
            container.status.status,
            container.pairing.pairedMac,
            permissionGranted,
            bluetoothOn,
        ) { settings, status, paired, granted, btOn ->
            HomeUiState(settings, status, paired, granted, btOn)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(null, BleStatus(), null, permissionGranted.value, bluetoothOn.value),
        )

    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied.asStateFlow()

    /** Re-reads permission and adapter state; call from ON_RESUME and after a permission result. */
    fun refreshEnvironment() {
        permissionGranted.value = BlePermissions.hasBleAccess(app)
        bluetoothOn.value = isBluetoothOn()
        reconcileServiceState()
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
                container.eventLog.info("Service was not running; restarting from saved setting")
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
            container.settings.setServiceEnabled(enabled)
            if (enabled) BleUnlockService.start(app) else BleUnlockService.stop(app)
        }
    }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch { container.settings.setPaused(paused) }
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

    private fun isBluetoothOn(): Boolean = app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}
