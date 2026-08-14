package com.yukarlo.unlockmymac.wear

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yukarlo.unlockmymac.service.ApprovalActionReceiver
import com.yukarlo.unlockmymac.service.UnlockNotifier
import com.yukarlo.unlockmymac.util.ApprovalRequestCodes

/**
 * Watch presentation for the shared service's notifications.
 *
 * The watch posts its own approval prompt rather than relying on the phone's being bridged across.
 * Bridging only works while the phone is reachable, which is precisely the case this whole design
 * exists to survive — the point of giving the watch its own key is that it works with the phone
 * downstairs.
 */
object WearNotifier : UnlockNotifier {
    private const val ONGOING_CHANNEL_ID = "wear_unlock_ongoing"

    /**
     * Bumped from `wear_unlock_approval`: a channel's vibration cannot be changed after it is
     * created, so adding a buzz to an install that has already run needs a new channel.
     */
    private const val APPROVAL_CHANNEL_ID = "wear_unlock_approval_v2"
    private const val LEGACY_APPROVAL_CHANNEL_ID = "wear_unlock_approval"

    /** Two short pulses, deliberately unlike a message buzz. */
    private val APPROVAL_PATTERN = longArrayOf(0, 200, 120, 200)

    /** [ApprovalActionReceiver] drops any negative id, which is how a probe answers to nothing. */
    private const val NO_CHALLENGE = -1L

    override val ongoingNotificationId = 2001
    override val approvalNotificationId = 2002

    override fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL_ID,
                context.getString(R.string.channel_ongoing),
                // LOW: the service must show a notification, but it should not buzz the wrist.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_ongoing_desc)
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                APPROVAL_CHANNEL_ID,
                context.getString(R.string.channel_approval),
                // HIGH: an approval request is time-boxed and useless if it goes unnoticed.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_approval_desc)
                // A prompt on the wrist is worth nothing if it is silent — the wearer is not
                // looking at the watch when the Mac decides to ask. Two short pulses, distinct
                // from a message buzz.
                enableVibration(true)
                vibrationPattern = APPROVAL_PATTERN
            },
        )

        manager.deleteNotificationChannel(LEGACY_APPROVAL_CHANNEL_ID)
    }

    override fun ongoing(
        context: Context,
        statusText: String,
    ): Notification =
        NotificationCompat
            .Builder(context, ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(context.getString(R.string.notification_ongoing_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .build()

    override fun approvalRequest(
        context: Context,
        challengeId: Long,
        macName: String?,
        originNodeId: String?,
    ): Notification = approvalRequest(context, challengeId, macName, originNodeId, probe = false)

    /**
     * Builds the approval prompt, optionally as a probe that answers to nothing.
     *
     * A probe is byte-for-byte the same notification as a real request — same channel, category,
     * priority and full-screen intent — because the thing under test is how the system chooses to
     * present it. Only the destination differs: a probe resolves nothing and never reaches the GATT
     * server, so it can be fired while the Mac is untouched.
     */
    fun approvalRequest(
        context: Context,
        challengeId: Long,
        macName: String?,
        originNodeId: String?,
        probe: Boolean,
    ): Notification {
        // Buzz here rather than from the approval screen. Measured on a watch asleep for a few
        // minutes: the full-screen intent did not launch, so the activity never ran and nothing
        // vibrated — the prompt sat silently in the shade until it was found by hand. Posting the
        // notification is the one step that always happens.
        buzz(context)

        val title =
            macName?.let { context.getString(R.string.notification_approval_title_mac, it) }
                ?: context.getString(R.string.notification_approval_title)
        return NotificationCompat
            .Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_approval_text))
            // CATEGORY_CALL for the full-screen treatment, having first rejected it for being
            // too intrusive. That was the wrong call: from Android 14 full-screen intents are
            // privileged for call and alarm categories, and a REMINDER is demoted to an ordinary
            // notification. On a sleeping watch that meant unlocking it, opening the shade,
            // tapping through and scrolling to find Approve — measured at 8 s, as long as the
            // entire Bluetooth handshake. A prompt that lives 60 s cannot cost 8 s to reach.
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(approvalScreenIntent(context, challengeId, macName, originNodeId, probe))
            // Nothing to bridge to and nothing worth bridging: the phone raises its own prompt
            // when it is the device being challenged.
            .setLocalOnly(true)
            // Raises the approval screen itself instead of waiting to be scrolled to and tapped.
            // A prompt with a 60s life is not much use sitting in a notification tray.
            //
            // Only while the screen is off. Taking over a display someone is already looking at is
            // not urgency, it is rudeness — and the Mac re-challenges, so it happens again and
            // again. Observed on the wrist as the watch appearing to hang: three seizures in four
            // minutes, each arriving mid-gesture. With the screen already on the notification is
            // right there to be tapped, so nothing is lost by staying out of the way.
            .apply {
                if (!isScreenOn(context)) {
                    setFullScreenIntent(
                        approvalScreenIntent(context, challengeId, macName, originNodeId, probe),
                        true,
                    )
                }
            }.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_deny),
                // A probe's buttons deliberately lead nowhere: the receiver drops any negative id,
                // so a test prompt cannot resolve a challenge or reach the Mac.
                approvalAction(
                    context,
                    if (probe) NO_CHALLENGE else challengeId,
                    approved = false,
                    originNodeId = originNodeId,
                ),
            ).addAction(
                android.R.drawable.ic_menu_send,
                context.getString(R.string.action_approve),
                approvalAction(
                    context,
                    if (probe) NO_CHALLENGE else challengeId,
                    approved = true,
                    originNodeId = originNodeId,
                ),
            ).build()
    }

    /**
     * Whether the wearer is currently looking at something.
     *
     * `isInteractive` rather than a display-state check: on a watch the ambient always-on face
     * counts as a screen that is technically on, and a prompt is still worth raising over that.
     */
    private fun isScreenOn(context: Context): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive == true

    /**
     * Vibrates directly instead of leaving it to the channel.
     *
     * The channel carries a vibration pattern already, but it only plays when the system chooses
     * to present the notification, and on a watch deep in doze it was observed not to. A prompt
     * the wearer never feels is the same as no prompt, so this does not delegate.
     */
    private fun buzz(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(APPROVAL_PATTERN, -1))
    }

    override fun cancelApproval(context: Context) {
        NotificationManagerCompat.from(context).cancel(approvalNotificationId)
    }

    private fun approvalScreenIntent(
        context: Context,
        challengeId: Long,
        macName: String?,
        originNodeId: String?,
        probe: Boolean,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            challengeId.toInt(),
            Intent(context, WearApprovalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(WearApprovalActivity.EXTRA_CHALLENGE_ID, challengeId)
                .putExtra(WearApprovalActivity.EXTRA_MAC_NAME, macName)
                .putExtra(WearApprovalActivity.EXTRA_ORIGIN_NODE, originNodeId)
                .putExtra(WearApprovalActivity.EXTRA_PROBE, probe),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            backgroundLaunchOptions(),
        )

    /**
     * Lets the system start the approval screen while nothing of ours is on screen.
     *
     * Without this, every post was followed by `Background activity launch blocked!` naming
     * `WearApprovalActivity`, with `balAllowedByPiCreator: BSP.NONE` — the framework's automatic
     * exemption for full-screen intents did not apply, because on this watch it is Samsung's
     * `com.samsung.android.wearable.sysui` that sends the PendingIntent rather than the
     * notification service itself, so from the launcher's point of view it is an ordinary
     * background send. The exemption has to be granted by whoever created the PendingIntent, which
     * is us. Granting `USE_FULL_SCREEN_INTENT` alone changed nothing: the block was recorded
     * unchanged after the app-op was set to `allow`.
     *
     * Note the *Creator* in the setter name. Its sibling,
     * `setPendingIntentBackgroundActivityStartMode`, is the sender's knob and is only legal at
     * `PendingIntent.send`; passing it here throws `IllegalArgumentException` from the binder call,
     * inside a GATT write callback, where nothing but a `W BluetoothGattServer` line survives.
     */
    private fun backgroundLaunchOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions
                .makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                ).toBundle()
        } else {
            null
        }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, WearMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun approvalAction(
        context: Context,
        challengeId: Long,
        approved: Boolean,
        originNodeId: String? = null,
    ): PendingIntent {
        val intent =
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = if (approved) ApprovalActionReceiver.ACTION_APPROVE else ApprovalActionReceiver.ACTION_DENY
                putExtra(ApprovalActionReceiver.EXTRA_CHALLENGE_ID, challengeId)
                putExtra(ApprovalActionReceiver.EXTRA_ORIGIN_NODE, originNodeId)
            }
        return PendingIntent.getBroadcast(
            context,
            // Per challenge as well as per decision. A fixed pair of codes only separated approve from
            // deny, so with FLAG_UPDATE_CURRENT below a probe prompt (challenge id -1) and a real
            // challenge overwrote each other's extras. See [ApprovalRequestCodes].
            ApprovalRequestCodes.forDecision(challengeId, approved),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
