package com.yukarlo.unlockmymac.wear

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.permissions.BlePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fires a fake approval prompt after a delay, so how the watch presents one can be tested without
 * involving the Mac.
 *
 * Every real test of this cost a lock, a walk and a display wake, and told us about the Bluetooth
 * handshake as much as about the prompt. The question worth isolating is narrower: given an approval
 * notification carrying a full-screen intent, does the system raise the screen or file it in the
 * shade? That needs no challenge and no central.
 *
 * The delay is the point. Firing while the app is on screen would prove nothing — an app with a
 * visible activity is allowed to start another one, which is exactly the permission the real case
 * lacks. The wait is there to be spent lowering your wrist and letting the screen sleep, so the
 * prompt arrives under the conditions that matter: our process alive as a foreground service, with
 * nothing of ours visible.
 */
object WearApprovalProbe {
    /** Long enough to lower a wrist and let the screen go out. */
    const val DELAY_SECONDS = 12

    /**
     * Positive, so the approval screen does not mistake it for a missing id, and far from any real
     * challenge, which are numbered from 1.
     */
    private const val PROBE_CHALLENGE_ID = 999_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /**
     * Cancels any probe already waiting and schedules a fresh one.
     *
     * Uses a plain coroutine rather than an alarm: exact alarms need a permission this app has no
     * other use for, and inexact ones can be held for minutes while idle, which would make the test
     * arrive long after the wrist was raised again. The foreground service keeps the process alive
     * for the seconds this needs.
     */
    // The notify below is guarded by the hasNotifications check inside the coroutine, which lint
    // cannot follow across the suspend boundary. Without this :wear:lintDebug fails the build — the
    // task that first caught this had never been run before CI existed.
    @SuppressLint("MissingPermission")
    fun schedule(context: Context) {
        val appContext = context.applicationContext
        pending?.cancel()
        pending =
            scope.launch {
                delay(DELAY_SECONDS * 1_000L)
                // Checked here rather than at the call site: the permission can be revoked during the
                // delay, and posting without it drops the notification silently — so the test prompt
                // would appear to do nothing at all, which is the one thing a test prompt must not do.
                if (!BlePermissions.hasNotifications(appContext)) {
                    appContext.container.eventLog.warn(
                        "Test prompt skipped: notification permission is not granted",
                    )
                    return@launch
                }
                val notification =
                    WearNotifier.approvalRequest(
                        context = appContext,
                        challengeId = PROBE_CHALLENGE_ID,
                        macName = null,
                        originNodeId = null,
                        probe = true,
                    )
                NotificationManagerCompat
                    .from(appContext)
                    .notify(WearNotifier.approvalNotificationId, notification)
            }
    }
}
