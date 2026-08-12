package com.yukarlo.unlockmymac.service

import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.permissions.BlePermissions
import org.json.JSONException
import org.json.JSONObject

/**
 * Handles approval prompts mirrored from the user's other device.
 *
 * Three messages: show a copy of someone else's prompt, take it away again, and — in the other
 * direction — carry an answer home to the device that actually holds the challenge.
 *
 * A mirrored prompt is a remote control, not a second credential. Approving here proves nothing
 * and signs nothing; it asks the challenged device to release the signature it already has. That
 * device re-checks the challenge is still live and unexpired before doing so, exactly as it would
 * for a tap on its own screen.
 */
class MirroredApprovalReceiver : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val body =
            try {
                JSONObject(String(messageEvent.data, Charsets.UTF_8))
            } catch (_: JSONException) {
                return
            }
        if (body.optInt("v", -1) != 1) return

        val challengeId = body.optLong("challengeId", -1L)
        if (challengeId < 0) return

        Log.i(TAG, "mirror message path=${messageEvent.path} challengeId=$challengeId")

        when (messageEvent.path) {
            ApprovalMirror.PATH_REQUEST -> {
                showMirroredPrompt(messageEvent.sourceNodeId, challengeId, body)
            }

            ApprovalMirror.PATH_DISMISS -> {
                container.notifier.cancelApproval(this)
                // Cancelling the notification does nothing to a full-screen approval activity,
                // so tell it too. Omitting this left the watch showing an answered question.
                ApprovalMirror.markDismissed(messageEvent.sourceNodeId, challengeId)
            }

            ApprovalMirror.PATH_DECISION -> {
                // The answer has come home. Resolve it against our own pending challenge, which
                // is the same path a tap on this device's own notification takes.
                BleUnlockService.resolveApproval(this, challengeId, body.optBoolean("approved", false))
            }

            else -> {
                Unit
            }
        }
    }

    private fun showMirroredPrompt(
        sourceNodeId: String,
        challengeId: Long,
        body: JSONObject,
    ) {
        if (!BlePermissions.hasNotifications(this)) return
        val macName = body.optString("macName").ifBlank { null }
        NotificationManagerCompat.from(this).notify(
            container.notifier.approvalNotificationId,
            container.notifier.approvalRequest(
                context = this,
                challengeId = challengeId,
                macName = macName,
                // Present means "this challenge belongs to that device" — the actions send the
                // answer there instead of trying to resolve a challenge we do not have.
                originNodeId = sourceNodeId,
            ),
        )
        container.eventLog.info("Showing an unlock request from your other device")
    }

    private companion object {
        const val TAG = "ApprovalMirror"
    }
}
