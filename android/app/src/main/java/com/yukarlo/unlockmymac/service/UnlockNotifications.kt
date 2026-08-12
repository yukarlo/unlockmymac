package com.yukarlo.unlockmymac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yukarlo.unlockmymac.MainActivity
import com.yukarlo.unlockmymac.R

/** Phone presentation for the shared service's notifications. */
object UnlockNotifications : UnlockNotifier {
    const val ONGOING_CHANNEL_ID = "ble_unlock_ongoing"
    const val APPROVAL_CHANNEL_ID = "ble_unlock_approval"
    const val ONGOING_NOTIFICATION_ID = 1001
    const val APPROVAL_NOTIFICATION_ID = 1002

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
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
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
        approved: Boolean,
    ): PendingIntent {
        val intent =
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = if (approved) ApprovalActionReceiver.ACTION_APPROVE else ApprovalActionReceiver.ACTION_DENY
                putExtra(ApprovalActionReceiver.EXTRA_CHALLENGE_ID, challengeId)
            }
        return PendingIntent.getBroadcast(
            context,
            // Distinct request codes so approve and deny do not overwrite each other.
            (challengeId.toInt() shl 1) or if (approved) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
