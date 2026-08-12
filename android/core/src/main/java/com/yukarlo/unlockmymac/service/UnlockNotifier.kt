package com.yukarlo.unlockmymac.service

import android.app.Notification
import android.content.Context

/**
 * How a form factor presents the service's two notifications.
 *
 * The orchestration in [BleUnlockService] — advertising, sessions, approvals, the expiry sweep —
 * is identical on a phone and a watch. What differs is presentation: a watch posts its own
 * approval prompt rather than inheriting the phone's bridged one, its channels are named for the
 * wrist, and tapping it opens a different activity. Splitting only that out keeps one copy of the
 * logic that actually matters.
 */
interface UnlockNotifier {
    val ongoingNotificationId: Int
    val approvalNotificationId: Int

    fun createChannels(context: Context)

    /** The persistent foreground-service notification. */
    fun ongoing(
        context: Context,
        statusText: String,
    ): Notification

    /** The time-boxed "may I unlock?" prompt, carrying Approve and Deny actions. */
    fun approvalRequest(
        context: Context,
        challengeId: Long,
        macName: String?,
    ): Notification

    fun cancelApproval(context: Context)
}
