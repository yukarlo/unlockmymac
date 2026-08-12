package com.yukarlo.unlockmymac.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Runtime permissions this app actually needs.
 *
 * `BLUETOOTH_SCAN` and `ACCESS_FINE_LOCATION` are absent on purpose: the phone is a peripheral,
 * never a scanner, so neither is required and asking for them would be gratuitous.
 */
object BlePermissions {
    /** Required before the foreground service can advertise or serve GATT. */
    val REQUIRED =
        arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

    /** Requested alongside the above; denial degrades the UI but does not break BLE. */
    val NOTIFICATIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS)

    val CAMERA = arrayOf(Manifest.permission.CAMERA)

    fun hasAll(
        context: Context,
        permissions: Array<String>,
    ): Boolean = permissions.all { isGranted(context, it) }

    fun missing(
        context: Context,
        permissions: Array<String>,
    ): List<String> = permissions.filterNot { isGranted(context, it) }

    fun hasBleAccess(context: Context): Boolean = hasAll(context, REQUIRED)

    fun hasCamera(context: Context): Boolean = hasAll(context, CAMERA)

    fun hasNotifications(context: Context): Boolean = hasAll(context, NOTIFICATIONS)

    private fun isGranted(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Doze / battery-optimisation exemption.
 *
 * A foreground service is not immune to being killed: OEM battery managers stop long-running
 * background apps aggressively, and this one was observed dying seven times in an hour on a
 * Samsung device. The exemption is the difference between the peripheral surviving overnight
 * and quietly disappearing.
 */
object BatteryOptimization {
    fun isExempt(context: Context): Boolean = context
        .getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Opens the system prompt asking the user to exempt this app. The exemption cannot be
     * granted programmatically — only the user can allow it.
     */
    @SuppressLint("BatteryLife") // Personal sideloaded build; see the manifest comment.
    fun requestExemptionIntent(packageName: String): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:$packageName".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Samsung's "Sleeping apps" list is separate from the Doze exemption and can still stop
     * the service. Sends the user to this app's system settings page to check it.
     */
    fun appSettingsIntent(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:$packageName".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
