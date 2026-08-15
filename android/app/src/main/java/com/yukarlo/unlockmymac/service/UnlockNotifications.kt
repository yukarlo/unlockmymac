package com.yukarlo.unlockmymac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yukarlo.unlockmymac.MainActivity
import com.yukarlo.unlockmymac.R
import com.yukarlo.unlockmymac.util.ApprovalRequestCodes

/** Phone presentation for the shared service's notifications. */
object UnlockNotifications : UnlockNotifier {
    const val ONGOING_CHANNEL_ID = "ble_unlock_ongoing"
    const val APPROVAL_CHANNEL_ID = "ble_unlock_approval"
    const val ONGOING_NOTIFICATION_ID = 1001
    const val APPROVAL_NOTIFICATION_ID = 1002

    private const val TAG = "UnlockNotifications"

    override val ongoingNotificationId = ONGOING_NOTIFICATION_ID
    override val approvalNotificationId = APPROVAL_NOTIFICATION_ID

    override fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL_ID,
                context.getString(R.string.channel_ongoing),
                // LOW: the service must show a notification, but it should not make noise.
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
                // HIGH: an approval request is time-boxed and useless if the user misses it.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_approval_desc)
            },
        )
    }

    /**
     * Plays whatever the approval channel would have played, since no notification is being posted.
     *
     * Reads the sound off the channel rather than hardcoding
     * `Settings.System.DEFAULT_NOTIFICATION_URI`, so a sound the user picked for *this* channel in system
     * settings is the sound they get — the same reason the app points at the system notification settings
     * instead of reimplementing them.
     *
     * Honours the ringer and Do Not Disturb the way the notification would have:
     *
     * - silent: nothing;
     * - vibrate: a short buzz instead of the sound;
     * - Do Not Disturb: nothing, unless the channel is allowed to break through.
     *
     * `USAGE_NOTIFICATION` so it lands on the notification stream and obeys that volume slider, rather
     * than coming out at media volume.
     */
    override fun playApprovalAlert(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = manager.getNotificationChannel(APPROVAL_CHANNEL_ID)

        val dndOn = manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (dndOn && channel?.canBypassDnd() != true) return

        val audio = context.getSystemService(AudioManager::class.java)
        when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> {
                return
            }

            AudioManager.RINGER_MODE_VIBRATE -> {
                vibrateBriefly(context)
                return
            }

            else -> {
                Unit
            }
        }

        val sound = channel?.sound ?: Settings.System.DEFAULT_NOTIFICATION_URI ?: return
        runCatching {
            RingtoneManager.getRingtone(context, sound)?.apply {
                audioAttributes =
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                play()
            }
        }.onFailure { Log.w(TAG, "Could not play the approval alert", it) }
    }

    private fun vibrateBriefly(context: Context) {
        val vibrator =
            context
                .getSystemService(VibratorManager::class.java)
                ?.defaultVibrator ?: return
        runCatching {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }.onFailure { Log.w(TAG, "Could not vibrate for the approval alert", it) }
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
    ): Notification {
        val title =
            macName?.let { context.getString(R.string.notification_approval_title_mac, it) }
                ?: context.getString(R.string.notification_approval_title)
        return NotificationCompat
            .Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_approval_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_deny),
                approvalAction(context, challengeId, approved = false, originNodeId = originNodeId),
            ).addAction(
                android.R.drawable.ic_menu_send,
                context.getString(R.string.action_approve),
                approvalAction(context, challengeId, approved = true, originNodeId = originNodeId),
            ).build()
    }

    override fun showApprovalOverlay(
        context: Context,
        challengeId: Long,
        macName: String?,
        originNodeId: String?,
    ) = ApprovalOverlay.show(context, challengeId, macName, originNodeId)

    override fun hideApprovalOverlay(context: Context) = ApprovalOverlay.hide(context)

    override fun cancelApproval(context: Context) {
        NotificationManagerCompat.from(context).cancel(APPROVAL_NOTIFICATION_ID)
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun approvalAction(
        context: Context,
        challengeId: Long,
        originNodeId: String? = null,
        approved: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = if (approved) ApprovalActionReceiver.ACTION_APPROVE else ApprovalActionReceiver.ACTION_DENY
                putExtra(ApprovalActionReceiver.EXTRA_CHALLENGE_ID, challengeId)
                putExtra(ApprovalActionReceiver.EXTRA_ORIGIN_NODE, originNodeId)
            }
        return PendingIntent.getBroadcast(
            context,
            // Distinct per challenge *and* per decision, or FLAG_UPDATE_CURRENT below rewrites an
            // existing intent's extras. See [ApprovalRequestCodes].
            ApprovalRequestCodes.forDecision(challengeId, approved),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
