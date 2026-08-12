package com.yukarlo.unlockmymac.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.service.BleUnlockService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Stops the watch broadcasting while it is not being worn.
 *
 * A proximity token is a claim that its owner is present. On a charger the watch claims exactly
 * the opposite, and anyone standing at the desk could unlock the Mac while the owner is in another
 * room — the failure this whole design exists to prevent, reintroduced by a bedside dock.
 *
 * Two signals, because neither is sufficient alone:
 *
 *  - **Charging.** Readable at any instant, and a watch on a charger is definitively off the
 *    wrist. This is what makes the state correct immediately at startup.
 *  - **The off-body sensor.** Covers the watch simply being taken off and set down. Measured on a
 *    Galaxy Watch 6: it reports only on a *transition*, never on registration — so on its own it
 *    cannot answer "is this worn right now?" for a watch that was already off when the app
 *    started, which is precisely the docked case.
 *
 * The user's own switch is never touched. Coming off stops the service; going back on restarts it
 * only if `serviceEnabled` was already true, so an intentional "off" survives being worn again.
 *
 * Fails open: with no sensor and no charger, broadcasting continues as before. A silent sensor
 * should not quietly render the watch useless, so the state is logged instead — visible in
 * Diagnostics rather than inferred from an unlock that never comes.
 */
class WearBodyMonitor(
    private val context: Context,
) : SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Null until the sensor reports an edge; treated as worn, so a missing sensor changes nothing. */
    private var sensorSaysWorn: Boolean? = null

    private var isCharging = false

    private var lastApplied: Boolean? = null

    private val available: Boolean
        get() = sensorSaysWorn != false && !isCharging

    private val powerReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                isCharging = intent.action == Intent.ACTION_POWER_CONNECTED
                Log.i(TAG, "Charging=$isCharging")
                apply()
            }
        }

    fun start() {
        isCharging = readChargingNow()
        Log.i(TAG, "Initial charging=$isCharging")

        context.registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )

        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        if (sensor == null) {
            context.container.eventLog.warn(
                "No off-body sensor on this watch; only charging will stop the broadcast",
            )
        } else {
            val registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Off-body sensor '${sensor.name}' registered=$registered")
        }

        apply()
    }

    /** The sticky battery broadcast answers this without waiting for a state change. */
    private fun readChargingNow(): Boolean {
        val status =
            context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val value = event?.values?.firstOrNull() ?: return
        // Logged raw: this watch reports only on a transition, and the encoding is worth seeing
        // rather than assuming.
        Log.i(TAG, "Off-body sensor value=$value")
        // 1.0 means on body, 0.0 off body.
        sensorSaysWorn = value >= 0.5f
        apply()
    }

    private fun apply() {
        val shouldBroadcast = available
        if (lastApplied == shouldBroadcast) return
        lastApplied = shouldBroadcast

        scope.launch {
            val container = context.container
            if (shouldBroadcast) {
                container.eventLog.info("Watch is being worn; resuming broadcast")
                // Only resume what the user actually asked for.
                if (container.settings.settings
                        .first()
                        .serviceEnabled
                ) {
                    BleUnlockService.start(context)
                }
            } else {
                val why = if (isCharging) "on the charger" else "not being worn"
                container.eventLog.info("Watch is $why; stopping broadcast")
                BleUnlockService.stop(context)
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    private companion object {
        const val TAG = "WearBody"
    }
}
