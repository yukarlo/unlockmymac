package com.yukarlo.unlockmymac.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yukarlo.unlockmymac.service.ApprovalActionReceiver
import com.yukarlo.unlockmymac.service.UnlockNotifier

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
    private const val APPROVAL_CHANNEL_ID = "wear_unlock_approval"

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
            },
        )
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
    ): Notification {
        val title =
            macName?.let { context.getString(R.string.notification_approval_title_mac, it) }
                ?: context.getString(R.string.notification_approval_title)
        return NotificationCompat
            .Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_approval_text))
            // Not CATEGORY_CALL on the wrist: Wear gives call notifications a full-screen
            // incoming-call treatment, which overstates what this is.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Nothing to bridge to and nothing worth bridging: the phone raises its own prompt
            // when it is the device being challenged.
            .setLocalOnly(true)
            // Raises the approval screen itself instead of waiting to be scrolled to and tapped.
            // A prompt with a 60s life is not much use sitting in a notification tray.
            .setFullScreenIntent(approvalScreenIntent(context, challengeId, macName), true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_deny),
                approvalAction(context, challengeId, approved = false),
            ).addAction(
                android.R.drawable.ic_menu_send,
                context.getString(R.string.action_approve),
                approvalAction(context, challengeId, approved = true),
            ).build()
    }

    override fun cancelApproval(context: Context) {
        NotificationManagerCompat.from(context).cancel(approvalNotificationId)
    }

    private fun approvalScreenIntent(
        context: Context,
        challengeId: Long,
        macName: String?,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            challengeId.toInt(),
            Intent(context, WearApprovalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(WearApprovalActivity.EXTRA_CHALLENGE_ID, challengeId)
                .putExtra(WearApprovalActivity.EXTRA_MAC_NAME, macName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
    ): PendingIntent {
        val intent =
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = if (approved) ApprovalActionReceiver.ACTION_APPROVE else ApprovalActionReceiver.ACTION_DENY
                putExtra(ApprovalActionReceiver.EXTRA_CHALLENGE_ID, challengeId)
            }
        return PendingIntent.getBroadcast(
            context,
            // Distinct request codes, or the second action would overwrite the first's extras.
            if (approved) 1 else 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
