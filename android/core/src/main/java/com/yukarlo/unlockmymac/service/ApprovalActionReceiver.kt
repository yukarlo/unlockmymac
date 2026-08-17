package com.yukarlo.unlockmymac.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yukarlo.unlockmymac.container

/**
 * Handles the Approve/Deny buttons on the approval notification.
 *
 * Every path withdraws *both* surfaces. The notification is the one being tapped, but a banner may be on
 * screen for the same request, and only one of the three paths below has anything downstream that would
 * take it down: a local challenge resolves through the service, which calls `onApprovalNoLongerValid` and
 * hides it. A mirrored one does not — this device holds nothing to resolve — so it relied on the holder
 * answering and echoing a dismiss back over the Wearable link. That left the card up for a round trip,
 * and indefinitely if the other device was unreachable: still on screen, still tappable, against a
 * question already answered.
 */
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
            withdrawPrompt(context)
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
            withdrawPrompt(context)
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
        withdrawPrompt(context)
    }

    /**
     * Takes down every surface for the request that was just answered.
     *
     * Hiding the banner here is belt-and-braces on the local path — the service does it too, on its way
     * through `onApprovalNoLongerValid` — and it is the only thing that does it on the mirrored path.
     * Both are idempotent, so answering once cannot leave a stale card and answering twice costs nothing.
     */
    private fun withdrawPrompt(context: Context) {
        context.container.notifier.cancelApproval(context)
        context.container.notifier.hideApprovalOverlay(context)
    }

    companion object {
        const val ACTION_APPROVE = "com.yukarlo.unlockmymac.APPROVE"
        const val ACTION_DENY = "com.yukarlo.unlockmymac.DENY"
        const val EXTRA_CHALLENGE_ID = "challenge_id"

        /** Set only on a mirrored prompt: the node whose challenge this actually is. */
        const val EXTRA_ORIGIN_NODE = "origin_node"
    }
}
