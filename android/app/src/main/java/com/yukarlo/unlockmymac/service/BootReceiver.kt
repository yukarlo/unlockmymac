package com.yukarlo.unlockmymac.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.permissions.BlePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the BLE peripheral after a reboot or an app update.
 *
 * `serviceEnabled` lives in DataStore and survives the process, but the service itself does
 * not. Without this the switch reads ON after every restart while nothing advertises, and the
 * Mac silently stops seeing the phone until the user happens to open the app.
 *
 * Starting a foreground service from the background is normally blocked on Android 12+, but
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are both on the exemption list. The
 * `connectedDevice` service type is also not among the types Android 15 forbids starting at
 * boot, so this path is legitimate rather than a loophole.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in HANDLED_ACTIONS) return

        // BOOT_COMPLETED arrives after the user's first unlock, so credential-encrypted
        // storage (and therefore DataStore) is readable here.
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val container = appContext.container
                val settings = container.settings.settings.first()

                if (!settings.serviceEnabled) return@launch

                if (!BlePermissions.hasBleAccess(appContext)) {
                    // Revoked while we were off. Starting would throw on Android 14+ anyway.
                    container.eventLog.warn("Boot: service enabled but Bluetooth permission missing")
                    return@launch
                }

                container.eventLog.info("Boot completed; restarting BLE service")
                BleUnlockService.start(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not restart service after boot", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val HANDLED_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )
    }
}
