package com.yukarlo.unlockmymac.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.yukarlo.unlockmymac.container
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject

/**
 * Records which Mac this watch should answer to.
 *
 * The other half of enrolment. Sending the public key to the phone tells the *Mac* about the
 * watch, but the watch also has to know the Mac, or `ChallengeCodec.validate` refuses every
 * challenge with `NOT_PAIRED` — which is exactly what happened the first time this ran.
 *
 * Only public identifiers arrive here: an installation id and a display name. Trust still rests
 * entirely on the keypair the watch generated for itself, which never leaves it.
 */
class WearEnrolmentReceiver : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PATH_ENROL_ACK) return

        val container = container
        val ack =
            try {
                JSONObject(String(messageEvent.data, Charsets.UTF_8))
            } catch (_: JSONException) {
                container.eventLog.warn("Enrolment acknowledgement was not valid JSON")
                return
            }

        if (ack.optInt("v", -1) != 1) {
            container.eventLog.warn("Enrolment acknowledgement used an unsupported version")
            return
        }

        val macInstallationId = ack.optString("macInstallationId")
        val macName = ack.optString("macName").ifBlank { "Mac" }
        if (macInstallationId.isBlank()) {
            container.eventLog.warn("Enrolment acknowledgement carried no Mac identity")
            return
        }

        runBlocking {
            container.pairing.savePairing(
                installationId = macInstallationId,
                macName = macName,
                nowMs = System.currentTimeMillis(),
            )
        }
        container.eventLog.info("Now paired with '$macName'")
    }

    private companion object {
        /** Must match `WearEnrolmentListener.PATH_ENROL_ACK` in the phone module. */
        const val PATH_ENROL_ACK = "/unlockmymac/enrol-ack"
    }
}
