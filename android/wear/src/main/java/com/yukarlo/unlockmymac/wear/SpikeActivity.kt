package com.yukarlo.unlockmymac.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/**
 * Throwaway spike answering one question: can this watch act as a BLE peripheral at all?
 *
 * Everything else about porting the app to Wear is ordinary work — the challenge codec, sessions,
 * keystore signer and settings have no UI or phone-specific imports, so they move as-is. Peripheral
 * support is the one thing that could make the whole idea impossible, and it cannot be answered
 * from documentation because it varies by OEM and by how much of the radio the companion-phone
 * link has already claimed.
 *
 * It advertises the real service UUID, so **the existing Mac app is the test harness** — no Mac
 * changes needed. The Mac will discover this, connect, attempt a handshake and fail, because
 * nothing here signs anything. That failure is expected and is not what we are measuring.
 *
 * Delete this module once the answer is known.
 */
class SpikeActivity : Activity() {
    private lateinit var output: TextView
    private val report = StringBuilder()
    private var gattServer: BluetoothGattServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output =
            TextView(this).apply {
                textSize = 11f
                setPadding(28, 28, 28, 28)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        setContentView(ScrollView(this).apply { addView(output) })

        val missing =
            REQUIRED_PERMISSIONS.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) runSpike() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            runSpike()
        } else {
            log("STOP: Bluetooth permission refused")
        }
    }

    @SuppressLint("MissingPermission")
    private fun runSpike() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null) {
            log("STOP: no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            log("STOP: Bluetooth is off — turn it on, then relaunch")
            return
        }
        log("adapter: on")

        // Reported, not enforced. A watch can refuse *multiple* advertisement sets and still
        // manage the single one this needs, so a false here is worth knowing but not fatal.
        log("multipleAdvertisement: ${adapter.isMultipleAdvertisementSupported}")

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            // This is the real verdict: no advertiser means no peripheral mode on this device.
            log("STOP: bluetoothLeAdvertiser is null — NO PERIPHERAL MODE")
            return
        }
        log("advertiser: available")

        val server = manager.openGattServer(this, serverCallback)
        if (server == null) {
            log("STOP: openGattServer returned null")
            return
        }
        gattServer = server
        log("gattServer: open")

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                CHALLENGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RESPONSE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                PAIRING_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        log("addService: ${server.addService(service)}")

        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()

        // A 128-bit UUID eats 18 of the 31 advertisement bytes, so the name goes in the scan
        // response — same constraint the phone app hit.
        val advertiseData =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        val scanResponse =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(true)
                .build()

        advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                log("ADVERTISING (tx=${settingsInEffect?.txPowerLevel})")
                log("Lock the Mac and wake the display to test.")
            }

            override fun onStartFailure(errorCode: Int) {
                log("STOP: advertising failed — ${advertiseError(errorCode)}")
            }
        }

    private val serverCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice?,
                status: Int,
                newState: Int,
            ) {
                // The strongest possible result: something actually reached the watch's GATT
                // server. Discovery alone only proves the advertisement escaped the radio.
                val state = if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "disconnected"
                log("central $state (status=$status)")
            }

            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService?,
            ) {
                log("serviceAdded: status=$status")
            }
        }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        gattServer?.close()
        gattServer = null
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            report.append(line).append('\n')
            output.text = report
        }
    }

    private fun advertiseError(code: Int): String =
        when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "payload over 31 bytes"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "no advertiser slots free"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already advertising"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "UNSUPPORTED — no peripheral mode"
            else -> "unknown code $code"
        }

    private companion object {
        const val TAG = "WearBleSpike"

        val REQUIRED_PERMISSIONS =
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )

        // The real UUIDs, so the existing Mac app discovers this without any changes.
        val SERVICE_UUID: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198765")
        val CHALLENGE_UUID: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198766")
        val RESPONSE_UUID: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198767")
        val PAIRING_UUID: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198768")
    }
}
