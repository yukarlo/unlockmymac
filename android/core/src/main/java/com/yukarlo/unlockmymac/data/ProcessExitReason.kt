package com.yukarlo.unlockmymac.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/**
 * Why the previous run of this process ended, according to the system.
 *
 * Worth having because guessing was actively misleading. A run that ends without `onDestroy` was being
 * reported as "killed by the system — check battery optimisation and Samsung's Sleeping apps list", and on
 * 2026-08-16 that advice was wrong in every particular: the app was Doze-whitelisted, standby bucket
 * `EXEMPTED`, `RUN_ANY_IN_BACKGROUND` allowed, and absent from Samsung's block list. `dumpsys activity
 * exit-info` said `reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)` — a development deploy, not a
 * battery manager. Eleven hours of not working were then explained by the one cause that no watchdog can
 * fix, since a force stop cancels scheduled work and suppresses service restart on purpose.
 *
 * So the app reads the same record the shell does. No permission needed: an app may always ask about its
 * own exits.
 */
object ProcessExitReason {
    /** A short description of the most recent exit, or null if the system has no record of one. */
    fun describeLast(context: Context): String? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info =
            runCatching {
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            }.getOrNull() ?: return null

        return buildString {
            append(describe(info.reason))
            // Present for a force stop, where it distinguishes a user's "Force stop" button from a
            // deploy, and for low memory. Skipped when it repeats the reason.
            info.description?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
    }

    /**
     * Advice worth giving, and only where it applies.
     *
     * A force stop and a low-memory kill call for opposite responses, and neither is helped by pointing at
     * battery settings — which is what the previous single catch-all message did.
     */
    fun adviceFor(context: Context): String? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val reason =
            runCatching {
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason
            }.getOrNull() ?: return null

        return when (reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            -> {
                "A force stop cancels scheduled work and stops the service being restarted, " +
                    "so nothing brings it back until the app is opened."
            }

            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            -> {
                "Killed for resources. Check battery optimisation and Samsung's Sleeping apps list."
            }

            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            -> {
                null
            }

            // Reinstalling is expected to restart everything; no advice needed.

            else -> {
                null
            }
        }
    }

    private fun describe(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "stopped itself"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
            ApplicationExitInfo.REASON_CRASH -> "crashed"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "force stopped"
            ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
            ApplicationExitInfo.REASON_FREEZER -> "frozen by the system"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "the package changed"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "the app was reinstalled"
            else -> "unknown reason"
        }
}
