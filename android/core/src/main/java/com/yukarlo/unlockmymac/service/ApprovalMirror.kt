package com.yukarlo.unlockmymac.service

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.yukarlo.unlockmymac.container
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Shows an approval prompt on the user's *other* device as well as the one being challenged.
 *
 * The Mac connects to whichever paired device has the best link, which is usually the phone on the
 * desk rather than the watch on the wrist. Without this the prompt lands on the device you would
 * have to pick up while the one already on your arm stays silent.
 *
 * Only the challenged device can answer: the challenge is bound to its BLE session and its key.
 * So a tap on the mirrored copy does not approve anything locally — it sends the decision back,
 * and the challenged device releases its own signature.
 *
 * Strictly additive. The challenged device always prompts itself first and never waits on, checks,
 * or acknowledges the mirror. A phone left in another room simply means the send fails and the
 * watch behaves exactly as it does alone.
 */
object ApprovalMirror {
    /**
     * Every send blocks on `Tasks.await`, and the callers include a `BroadcastReceiver`, whose
     * `onReceive` runs on the main thread — Play services throws outright there. Owning one
     * thread here means no call site can get that wrong, which one of them already did.
     */
    private val io = Executors.newSingleThreadExecutor()

    private const val TAG = "ApprovalMirror"

    const val PATH_REQUEST = "/unlockmymac/approval-request"
    const val PATH_DECISION = "/unlockmymac/approval-decision"
    const val PATH_DISMISS = "/unlockmymac/approval-dismiss"

    /** A challenge is only identified by its id *together with* the device that issued it. */
    data class DismissedApproval(
        val nodeId: String,
        val challengeId: Long,
    )

    private val _dismissed = MutableSharedFlow<DismissedApproval>(extraBufferCapacity = 8)

    /**
     * The last mirrored challenge the originating device said was finished with.
     *
     * A dismiss can only cancel a notification, and a mirrored prompt may be showing as a
     * full-screen activity instead. This lets that UI notice and close itself.
     */
    val dismissed: SharedFlow<DismissedApproval> = _dismissed.asSharedFlow()

    fun markDismissed(
        nodeId: String,
        challengeId: Long,
    ) {
        Log.i(TAG, "markDismissed(node=$nodeId, challenge=$challengeId)")
        _dismissed.tryEmit(DismissedApproval(nodeId, challengeId))
    }

    /** Asks nearby paired devices to show this prompt too. */
    fun broadcastRequest(
        context: Context,
        challengeId: Long,
        macName: String?,
    ) {
        val appContext = context.applicationContext
        val payload =
            JSONObject()
                .put("v", 1)
                .put("challengeId", challengeId)
                .put("macName", macName ?: "")
                .toString()
        send(appContext, PATH_REQUEST, payload)
    }

    /** Tells them the question has been answered, so their copy can disappear. */
    fun broadcastDismiss(
        context: Context,
        challengeId: Long,
    ) {
        send(
            context.applicationContext,
            PATH_DISMISS,
            JSONObject().put("v", 1).put("challengeId", challengeId).toString(),
        )
    }

    /** Sends this device's answer back to the device actually holding the challenge. */
    fun sendDecision(
        context: Context,
        nodeId: String,
        challengeId: Long,
        approved: Boolean,
    ) {
        val payload =
            JSONObject()
                .put("v", 1)
                .put("challengeId", challengeId)
                .put("approved", approved)
                .toString()
        // Application context, not the caller's: `WearApprovalActivity` sends its answer and
        // finishes in the same breath, so anything else would strand a dead Activity on this
        // executor until the Data Layer call returned.
        val appContext = context.applicationContext
        io.execute {
            runCatching {
                Tasks.await(
                    Wearable
                        .getMessageClient(appContext)
                        .sendMessage(nodeId, PATH_DECISION, payload.toByteArray(Charsets.UTF_8)),
                )
            }.onFailure {
                appContext.container.eventLog.warn("Could not send the approval decision: ${it.message}")
            }
        }
    }

    /**
     * Sends only to nodes reported as nearby.
     *
     * The Data Layer will happily route through Google's servers, which would put an unlock prompt
     * on a phone in another building. Approving is meant to mean "I am here", so a prompt only
     * belongs on a device that is genuinely in the same place as the one being challenged.
     */
    private fun send(
        context: Context,
        path: String,
        payload: String,
    ) {
        io.execute {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                val bytes = payload.toByteArray(Charsets.UTF_8)
                val messageClient = Wearable.getMessageClient(context)
                val nearby = nodes.filter { it.isNearby }
                Log.i(TAG, "send $path to ${nearby.size} nearby of ${nodes.size} node(s)")
                nearby.forEach { node ->
                    runCatching { Tasks.await(messageClient.sendMessage(node.id, path, bytes)) }
                }
            }
        }
        // Deliberately silent on failure: there is nothing to recover, and the device being
        // challenged has already shown its own prompt.
    }
}
