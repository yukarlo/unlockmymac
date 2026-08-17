package com.yukarlo.unlockmymac.data

import android.content.Context

/**
 * Records whether the last run of the BLE service ended on purpose.
 *
 * `onDestroy` already tells apart "turned off in the app" from "stopped unexpectedly" — but only when it
 * runs. A `SIGKILL` skips it entirely, and that is the case that actually hurts: on 2026-08-16 the process
 * was killed some time after 00:04 and did not exist again until 10:59, and the app's own log showed an
 * eleven-hour hole with no explanation in it. Working out what had happened needed `ps -o etime` from a
 * laptop, which is not a diagnostic anyone has when they are just trying to unlock a Mac.
 *
 * So the marker is set when the service starts and cleared when it stops in an orderly way. Finding it
 * still set at the next start means the run in between was killed outright.
 *
 * `SharedPreferences` with `commit()`, deliberately, not the `DataStore` everything else uses. This has to
 * be durable *before* a kill that arrives without warning, and `DataStore` writes are asynchronous — the
 * one thing this must not be. `commit()` blocks, which is normally the wrong choice and is exactly the
 * right one here.
 */
class ServiceRunMarker(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences("service_run", Context.MODE_PRIVATE)

    /**
     * Marks a run as started, reporting whether the previous one ended abruptly.
     *
     * @return true if the marker was still set, meaning the last run was killed without `onDestroy`.
     */
    fun beginRun(): Boolean {
        val previousRunDied = prefs.getBoolean(KEY_RUNNING, false)
        prefs.edit().putBoolean(KEY_RUNNING, true).commit()
        return previousRunDied
    }

    /** Marks a run as ended on purpose, so the next start does not report it as a kill. */
    fun endRunCleanly() {
        prefs.edit().putBoolean(KEY_RUNNING, false).commit()
    }

    private companion object {
        const val KEY_RUNNING = "service_running"
    }
}
