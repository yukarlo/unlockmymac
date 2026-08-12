package com.yukarlo.unlockmymac.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.yukarlo.unlockmymac.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64

/**
 * Hands this watch's public key to the phone so it can vouch for it with the Mac.
 *
 * Only the public half travels. The watch's private key is generated in its own AndroidKeyStore
 * and is non-exportable, which is the whole reason the watch ends up an independent credential
 * rather than a copy of the phone: afterwards it authenticates on its own, and revoking it does
 * not disturb the phone.
 *
 * Sent with `MessageClient` rather than `DataClient` on purpose. `DataItem`s are persistent and
 * sync whenever the link returns, so a request made while disconnected would surface at some
 * arbitrary later time. This project has already been bitten once by a stale request arriving
 * hours late; a message that simply fails when there is no route is the right semantics.
 */
object WearEnrolmentSender {
    const val PATH_ENROL_REQUEST = "/unlockmymac/enrol-request"

    sealed interface Result {
        object Sent : Result

        object NoPhoneReachable : Result

        class Failed(
            val reason: String,
        ) : Result
    }

    // `Tasks.await` rather than the coroutines-play-services `await()` extension: it keeps the
    // module off another dependency, and this is already confined to a background dispatcher.
    suspend fun sendPublicKey(context: Context): Result =
        withContext(Dispatchers.IO) {
            val container = context.container
            try {
                container.signer.ensureKey()
                val identity = container.signer.identity()
                val deviceId = container.pairing.requireDeviceId()

                val payload =
                    JSONObject()
                        .put("v", 1)
                        .put("deviceId", deviceId)
                        .put("name", android.os.Build.MODEL ?: "Watch")
                        .put("publicKey", Base64.getEncoder().encodeToString(identity.publicKeyDer))
                        .toString()
                        .toByteArray(Charsets.UTF_8)

                val nodes: List<Node> = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                if (nodes.isEmpty()) return@withContext Result.NoPhoneReachable

                val messageClient = Wearable.getMessageClient(context)
                var delivered = false
                for (node in nodes) {
                    runCatching {
                        Tasks.await(messageClient.sendMessage(node.id, PATH_ENROL_REQUEST, payload))
                    }.onSuccess { delivered = true }
                }
                if (delivered) {
                    container.eventLog.info("Sent this watch's public key to the phone for enrolment")
                    Result.Sent
                } else {
                    Result.NoPhoneReachable
                }
            } catch (error: Exception) {
                container.eventLog.warn("Could not send the public key to the phone: ${error.message}")
                Result.Failed(error.message ?: "unknown error")
            }
        }
}
