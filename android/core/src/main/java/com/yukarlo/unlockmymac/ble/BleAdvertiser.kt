package com.yukarlo.unlockmymac.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.yukarlo.unlockmymac.data.AdvertiseMode
import com.yukarlo.unlockmymac.data.AdvertisingState
import com.yukarlo.unlockmymac.data.BleStatusRepository
import com.yukarlo.unlockmymac.data.EventLog
import com.yukarlo.unlockmymac.permissions.BlePermissions

/**
 * Connectable BLE advertisement carrying nothing but the service UUID.
 *
 * The UUID is a discovery hint only — it is public, copyable, and worthless as a credential.
 * Everything that matters happens after the Mac connects and the GATT challenge succeeds.
 */
class BleAdvertiser(
    private val context: Context,
    private val status: BleStatusRepository,
    private val eventLog: EventLog,
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null

    /**
     * The mode the live advertisement was started with, so a changed setting can be noticed.
     *
     * An `AdvertiseSettings` is fixed once handed to the controller; changing the interval means
     * tearing the advertisement down and raising a new one.
     */
    private var activeMode: AdvertiseMode? = null

    private val callback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                status.setAdvertising(AdvertisingState.ADVERTISING)
                eventLog.info("Advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                status.setAdvertising(AdvertisingState.FAILED, describeError(errorCode))
                eventLog.error("Advertising failed: ${describeError(errorCode)}")
                advertiser = null
            }
        }

    val isAdvertising: Boolean get() = advertiser != null

    @SuppressLint("MissingPermission") // Guarded by BlePermissions.hasBleAccess below.
    fun start(mode: AdvertiseMode) {
        if (advertiser != null) {
            // Already up in the mode asked for; nothing to do.
            if (activeMode == mode) return

            // Already up in a *different* mode. This used to return here too, which silently
            // discarded the change: `applyState` calls this on every settings edit, so toggling
            // fast discovery did nothing until the advertisement happened to be torn down for
            // some other reason — in practice the restart after the next connection. Measured as
            // a toggle that appeared to work only from the following unlock onwards.
            eventLog.info("Advertising mode changed to ${mode.name.lowercase()}")
            restart(mode)
            return
        }

        if (!BlePermissions.hasBleAccess(context)) {
            status.setAdvertising(AdvertisingState.NO_PERMISSION)
            eventLog.warn("Cannot advertise: Bluetooth permissions not granted")
            return
        }
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            status.setAdvertising(AdvertisingState.BLUETOOTH_OFF)
            eventLog.warn("Cannot advertise: Bluetooth is off")
            return
        }
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            status.setAdvertising(AdvertisingState.FAILED, "BLE advertising unsupported")
            eventLog.error("This device cannot act as a BLE peripheral")
            return
        }

        status.setAdvertising(AdvertisingState.STARTING)

        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(
                    when (mode) {
                        AdvertiseMode.LOW_POWER -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                        AdvertiseMode.BALANCED -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
                    },
                ).setTxPowerLevel(
                    // Always HIGH (+1 dBm), deliberately not tied to the mode above.
                    //
                    // ADVERTISE_TX_POWER_LOW is -15 dBm, which put a phone on the same desk at
                    // -85 dBm — too weak for the Mac to hold a GATT connection. MEDIUM is -7 dBm.
                    //
                    // Power used to follow the advertising mode, which quietly made the battery
                    // toggle an 8 dB range lever: with it off, a phone that normally reads -60 dBm
                    // came in at -74 dBm, both connect attempts stalled, and the unlock took an
                    // extra nine seconds. Nothing was gained by that — battery cost is dominated
                    // by how often the radio transmits, and the mode above already cuts that
                    // roughly fourfold by advertising every second instead of every 250 ms.
                    AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                ).setConnectable(true)
                .setTimeout(0)
                .build()

        // A 128-bit UUID takes 18 of the 31 advertisement bytes, so the name goes in the scan
        // response instead; including it in the primary payload overflows and fails to start.
        val advertiseData =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(BleUuids.SERVICE))
                .build()

        // setIncludeDeviceName copies the *adapter* name, which we never rename (that is a
        // system-wide change). Only include it when it fits, or the whole start request fails.
        val adapterNameBytes =
            runCatching { adapter.name.orEmpty() }
                .getOrDefault("")
                .toByteArray(Charsets.UTF_8)
                .size
        val scanResponse =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(adapterNameBytes in 1..MAX_SCAN_NAME_BYTES)
                .build()

        advertiser = leAdvertiser
        activeMode = mode
        runCatching { leAdvertiser.startAdvertising(settings, advertiseData, scanResponse, callback) }
            .onFailure {
                advertiser = null
                activeMode = null
                status.setAdvertising(AdvertisingState.FAILED, it.message)
                eventLog.error("Advertising could not start: ${it.message}")
            }
    }

    @SuppressLint("MissingPermission") // Stopping is harmless if the permission was revoked.
    fun stop() {
        val current = advertiser ?: return
        advertiser = null
        activeMode = null
        runCatching { current.stopAdvertising(callback) }
            .onFailure { Log.w(TAG, "stopAdvertising threw", it) }
        status.setAdvertising(AdvertisingState.STOPPED)
        eventLog.info("Advertising stopped")
    }

    /**
     * Tears the advertisement down and brings it back up.
     *
     * Required after a central disconnects: the controller stops a connectable advertisement
     * when the connection is established and never resumes it, so without this the phone is
     * discoverable exactly once per app start.
     */
    fun restart(mode: AdvertiseMode) {
        stop()
        start(mode)
    }

    /**
     * Marks the advertisement as suspended for the duration of a connection, so the UI stops
     * claiming we are discoverable when the radio says otherwise.
     */
    fun markPausedByConnection() {
        // Status only. The handle is kept so [restart] can still issue a real stopAdvertising —
        // reusing the callback without stopping first returns ADVERTISE_FAILED_ALREADY_STARTED.
        if (advertiser != null) status.setAdvertising(AdvertisingState.PAUSED_CONNECTED)
    }

    private fun describeError(errorCode: Int): String =
        when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertisement data too large"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
            else -> "error $errorCode"
        }

    private companion object {
        const val TAG = "BleAdvertiser"

        /** Scan response is 31 bytes; leave room for the AD type and length octets. */
        const val MAX_SCAN_NAME_BYTES = 26
    }
}
