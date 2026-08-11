package com.yukarlo.unlockmymac.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Approve/Deny buttons on the approval notification. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val id = intent.getLongExtra(EXTRA_CHALLENGE_ID, -1L)
        if (id < 0) return
        val approved =
            when (intent.action) {
                ACTION_APPROVE -> true
                ACTION_DENY -> false
                else -> return
            }
        // Routed through the service so approval and the GATT server share one instance.
        context.startService(
            Intent(context, BleUnlockService::class.java).apply {
                action = BleUnlockService.ACTION_RESOLVE_APPROVAL
                putExtra(BleUnlockService.EXTRA_CHALLENGE_ID, id)
                putExtra(BleUnlockService.EXTRA_APPROVED, approved)
            },
        )
        UnlockNotifications.cancelApproval(context)
    }

    companion object {
        const val ACTION_APPROVE = "com.yukarlo.unlockmymac.APPROVE"
        const val ACTION_DENY = "com.yukarlo.unlockmymac.DENY"
        const val EXTRA_CHALLENGE_ID = "challenge_id"
    }
}
