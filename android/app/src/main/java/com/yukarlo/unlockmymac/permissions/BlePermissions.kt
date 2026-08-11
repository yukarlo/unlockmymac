package com.yukarlo.unlockmymac.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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
