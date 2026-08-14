package com.yukarlo.unlockmymac.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yukarlo.unlockmymac.container

/** Handles the Approve/Deny buttons on the approval notification. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val id = intent.getLongExtra(EXTRA_CHALLENGE_ID, -1L)
        if (id < 0) {
            // Withdraw first. There is no challenge to resolve — this is the test prompt, which uses a
            // negative id — but the notification is real, and returning without cancelling left it stuck
            // in the shade with buttons that did nothing.
            context.container.notifier.cancelApproval(context)
            return
        }
        val approved =
            when (intent.action) {
                ACTION_APPROVE -> true
                ACTION_DENY -> false
                else -> return
            }
        val originNode = intent.getStringExtra(EXTRA_ORIGIN_NODE)
        if (originNode != null) {
            // This prompt was a copy of another device's challenge; we hold nothing to resolve.
            ApprovalMirror.sendDecision(context, originNode, id, approved)
            context.container.notifier.cancelApproval(context)
            return
        }

        // Routed through the service so approval and the GATT server share one instance.
        context.startService(
            Intent(context, BleUnlockService::class.java).apply {
                action = BleUnlockService.ACTION_RESOLVE_APPROVAL
                putExtra(BleUnlockService.EXTRA_CHALLENGE_ID, id)
                putExtra(BleUnlockService.EXTRA_APPROVED, approved)
            },
        )
        context.container.notifier.cancelApproval(context)
    }

    companion object {
        const val ACTION_APPROVE = "com.yukarlo.unlockmymac.APPROVE"
        const val ACTION_DENY = "com.yukarlo.unlockmymac.DENY"
        const val EXTRA_CHALLENGE_ID = "challenge_id"

        /** Set only on a mirrored prompt: the node whose challenge this actually is. */
        const val EXTRA_ORIGIN_NODE = "origin_node"
    }
}
