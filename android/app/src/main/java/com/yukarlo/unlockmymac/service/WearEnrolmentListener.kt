package com.yukarlo.unlockmymac.service

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.yukarlo.unlockmymac.ble.EnrolmentCodec
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.pairing.EnrolmentCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Turns a watch's public key into a signed statement the Mac will accept.
 *
 * The watch has no camera, so it cannot scan the Mac's pairing QR. Instead it sends its own public
 * key here, and this phone — whose key the Mac already trusts — vouches for it. Nothing secret
 * crosses the link: the watch keeps a non-exportable key of its own, which is what makes it an
 * independent credential afterwards rather than a copy of this phone.
 *
 * Signing here is not the same as authorising. The Mac still checks the signature against the key
 * it has stored, checks the offer names *it*, checks the offer is fresh, and only looks for one at
 * all when the user asks it to from an unlocked session.
 */
class WearEnrolmentListener : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PATH_ENROL_REQUEST) return

        val container = container
        val request =
            try {
                JSONObject(String(messageEvent.data, Charsets.UTF_8))
            } catch (_: JSONException) {
                container.eventLog.warn("Watch enrolment request was not valid JSON")
                return
            }

        if (request.optInt("v", -1) != 1) {
            container.eventLog.warn("Watch enrolment request used an unsupported version")
            return
        }

        val watchDeviceId = request.optString("deviceId")
        val watchName = request.optString("name").ifBlank { "Watch" }
        val publicKeyDer =
            try {
                Base64.getDecoder().decode(request.optString("publicKey"))
            } catch (_: IllegalArgumentException) {
                container.eventLog.warn("Watch enrolment request carried an unreadable public key")
                return
            }

        if (publicKeyDer.size != EnrolmentCodec.PUBLIC_KEY_DER_BYTES) {
            container.eventLog.warn("Watch enrolment request carried a key of the wrong length")
            return
        }

        // Vouching only means anything if this phone is itself trusted by a Mac.
        val paired = runBlocking { container.pairing.pairedMac.first() }
        if (paired == null) {
            container.eventLog.warn("Ignored a watch enrolment request: this phone is not paired with a Mac")
            return
        }

        val ownDeviceId = runBlocking { container.pairing.requireDeviceId() }
        if (watchDeviceId.equals(ownDeviceId, ignoreCase = true)) {
            // Refusing this is what stops a device re-authorising itself under a new key.
            container.eventLog.warn("Ignored a watch enrolment request that named this phone")
            return
        }

        val issuedAt = System.currentTimeMillis()
        val offerBytes =
            EnrolmentCodec.buildOffer(
                macInstallationId = paired.installationId,
                deviceId = watchDeviceId,
                deviceName = watchName,
                publicKeyDer = publicKeyDer,
                issuedAtMs = issuedAt,
                sign = { payload -> runCatching { container.signer.sign(payload) }.getOrNull() },
            )

        if (offerBytes == null) {
            container.eventLog.error("Could not sign the enrolment offer for '$watchName'")
            return
        }

        container.enrolmentCoordinator.stage(
            EnrolmentCoordinator.PendingOffer(
                offerBytes = offerBytes,
                deviceName = watchName,
                deviceId = watchDeviceId,
                expiresAtMs = issuedAt + EnrolmentCodec.OFFER_TTL_MS,
            ),
        )
        container.eventLog.info(
            "Vouched for '$watchName'; open Add a device on ${paired.name} within 5 minutes",
        )

        // Tell the watch which Mac to trust. Enrolment is otherwise one-directional: the Mac
        // would learn the watch's key while the watch still had no paired Mac, so it refused
        // every challenge with NOT_PAIRED and could never actually unlock anything.
        replyWithMacIdentity(
            nodeId = messageEvent.sourceNodeId,
            macInstallationId = paired.installationId,
            macName = paired.name,
        )
    }

    private fun replyWithMacIdentity(
        nodeId: String,
        macInstallationId: String,
        macName: String,
    ) {
        val payload =
            JSONObject()
                .put("v", 1)
                .put("macInstallationId", macInstallationId)
                .put("macName", macName)
                .toString()
                .toByteArray(Charsets.UTF_8)
        runCatching {
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(nodeId, PATH_ENROL_ACK, payload),
            )
        }.onFailure {
            container.eventLog.warn("Could not tell the watch which Mac to trust: ${it.message}")
        }
    }

    private companion object {
        /** Must match `WearEnrolmentSender.PATH_ENROL_REQUEST` in the wear module. */
        const val PATH_ENROL_REQUEST = "/unlockmymac/enrol-request"

        /** Must match `WearEnrolmentReceiver.PATH_ENROL_ACK` in the wear module. */
        const val PATH_ENROL_ACK = "/unlockmymac/enrol-ack"
    }
}
