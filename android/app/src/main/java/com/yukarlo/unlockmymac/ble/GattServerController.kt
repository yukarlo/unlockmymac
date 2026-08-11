package com.yukarlo.unlockmymac.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.yukarlo.unlockmymac.crypto.KeystoreSigner
import com.yukarlo.unlockmymac.data.AuthOutcome
import com.yukarlo.unlockmymac.data.BleStatusRepository
import com.yukarlo.unlockmymac.data.EventLog
import com.yukarlo.unlockmymac.pairing.PairingCoordinator
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.util.challengeTag
import java.util.Collections

/** Everything the GATT server needs to know about app state at the moment of a request. */
class GattContext(
    val pairedMacInstallationId: String?,
    val deviceId: String,
    val deviceName: String,
    val requireApproval: Boolean,
    val challengeTtlMs: Long,
)

/** Callbacks the service wires up to notifications and persistence. */
interface GattServerListener {
    /** A challenge is parked waiting for the user to tap Approve. */
    fun onApprovalRequested(pending: PendingChallenge)

    /** The Mac completed a pairing read; persist the pairing. */
    fun onPaired(
        macInstallationId: String,
        macName: String,
    )

    /**
     * The number of connected centrals changed.
     *
     * A connectable advertisement stops the moment a central connects, and the Android stack
     * does not resume it on disconnect. The service uses this to restart advertising, without
     * which the phone becomes permanently undiscoverable after the very first connection.
     */
    fun onConnectedCentralsChanged(count: Int)
}

/**
 * GATT server implementing the Android half of the challenge-response protocol.
 *
 * Design rules that matter for security:
 * - Sessions are keyed by [BluetoothDevice.getAddress], which is *transport bookkeeping only*.
 *   Identity comes solely from a signature over a challenge naming our device id and the paired
 *   Mac. A spoofed address gets an attacker no further than an unsigned connection.
 * - Every rejection returns the same opaque [GattStatus.REJECTED] so a stranger learns nothing
 *   about which check failed.
 * - The signature is computed once and cached for ATT read-blob continuations. ECDSA is
 *   randomised, so signing per fragment would return a spliced blob that cannot verify.
 */
class GattServerController(
    private val context: Context,
    private val sessions: ChallengeSessions,
    private val signer: KeystoreSigner,
    private val pairingCoordinator: PairingCoordinator,
    private val status: BleStatusRepository,
    private val eventLog: EventLog,
    private val contextProvider: () -> GattContext,
    private val listener: GattServerListener,
) {
    private var gattServer: BluetoothGattServer? = null

    private val connected: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Pairing identity blob, staged on a valid claim write and served on the following read. */
    @Volatile
    private var stagedPairingResponse: ByteArray? = null

    val isOpen: Boolean get() = gattServer != null

    @SuppressLint("MissingPermission") // Callers check BlePermissions.hasBleAccess first.
    fun open(): Boolean {
        if (gattServer != null) return true
        if (!BlePermissions.hasBleAccess(context)) {
            eventLog.warn("Cannot open GATT server: Bluetooth permissions not granted")
            return false
        }
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val server = manager.openGattServer(context, callback)
        if (server == null) {
            eventLog.error("openGattServer returned null")
            return false
        }
        gattServer = server

        val service = BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleUuids.CHALLENGE,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleUuids.RESPONSE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleUuids.PAIRING,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )

        if (!server.addService(service)) {
            eventLog.error("Could not add GATT service")
            close()
            return false
        }
        eventLog.info("GATT server open")
        return true
    }

    @SuppressLint("MissingPermission") // Closing is safe even if permission was revoked.
    fun close() {
        val server = gattServer ?: return
        gattServer = null
        stagedPairingResponse = null
        connected.clear()
        sessions.clear()
        status.setConnectedCentrals(0)
        runCatching { server.close() }.onFailure { Log.w(TAG, "GATT server close threw", it) }
        eventLog.info("GATT server closed")
    }

    /** Approves or denies a parked challenge. Called from the UI or the notification action. */
    fun resolveApproval(
        id: Long,
        approved: Boolean,
    ) {
        val pending = sessions.setApproval(id, approved) ?: return
        val tag = challengeTag(pending.request.rawPayload)
        if (approved) {
            eventLog.info("Challenge $tag approved by user")
        } else {
            eventLog.warn("Challenge $tag denied by user")
            status.recordAuth(AuthOutcome.DENIED, tag, "Denied by user")
        }
        status.setPendingApproval(null)
    }

    private val callback =
        object : BluetoothGattServerCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                gattStatus: Int,
                newState: Int,
            ) {
                val key = device.address
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connected.add(key)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connected.remove(key)
                        // Everything tied to this connection dies with it: no cross-connection reuse.
                        sessions.remove(key)
                        stagedPairingResponse = null
                    }
                }
                val count = connected.size
                status.setConnectedCentrals(count)
                listener.onConnectedCentralsChanged(count)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                // Long/queued writes are refused outright: the payloads here are small enough to
                // arrive whole, and reassembling attacker-controlled fragments buys nothing.
                if (preparedWrite || offset != 0 || value == null) {
                    respond(device, requestId, GattStatus.REJECTED, offset, responseNeeded)
                    return
                }
                when (characteristic.uuid) {
                    BleUuids.CHALLENGE -> handleChallengeWrite(device, requestId, value, responseNeeded)
                    BleUuids.PAIRING -> handlePairingWrite(device, requestId, value, responseNeeded)
                    else -> respond(device, requestId, GattStatus.REJECTED, offset, responseNeeded)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                when (characteristic.uuid) {
                    BleUuids.RESPONSE -> handleResponseRead(device, requestId, offset)
                    BleUuids.PAIRING -> handlePairingRead(device, requestId, offset)
                    else -> respond(device, requestId, GattStatus.REJECTED, offset, true)
                }
            }

            override fun onExecuteWrite(
                device: BluetoothDevice,
                requestId: Int,
                execute: Boolean,
            ) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, true)
            }
        }

    private fun handleChallengeWrite(
        device: BluetoothDevice,
        requestId: Int,
        value: ByteArray,
        responseNeeded: Boolean,
    ) {
        val ctx = contextProvider()
        val parsed = ChallengeCodec.parse(value)
        if (parsed is ParseResult.Invalid) {
            reject(device, requestId, responseNeeded, parsed.reason, "malformed challenge")
            return
        }
        val request = (parsed as ParseResult.Valid).value
        val tag = challengeTag(request.rawPayload)

        val invalid =
            ChallengeCodec.validate(
                request = request,
                pairedMacInstallationId = ctx.pairedMacInstallationId,
                ownDeviceId = ctx.deviceId,
                nowMs = System.currentTimeMillis(),
            )
        if (invalid != null) {
            reject(device, requestId, responseNeeded, invalid, "challenge $tag")
            return
        }

        val refused =
            sessions.offer(
                connectionKey = device.address,
                request = request,
                ttlMs = ctx.challengeTtlMs,
                requiresApproval = ctx.requireApproval,
            )
        if (refused != null) {
            reject(device, requestId, responseNeeded, refused, "challenge $tag")
            return
        }

        // Only report success once the session state is committed.
        status.recordChallenge(System.currentTimeMillis())
        eventLog.info("Challenge $tag accepted")
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, responseNeeded)

        if (ctx.requireApproval) {
            sessions.current(device.address)?.let(listener::onApprovalRequested)
        }
    }

    private fun handleResponseRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
    ) {
        when (val claim = sessions.claimForSigning(device.address)) {
            is ChallengeSessions.Result.Refused -> {
                val gattStatus =
                    if (claim.reason == RejectReason.AWAITING_APPROVAL) {
                        GattStatus.PENDING_APPROVAL
                    } else {
                        GattStatus.REJECTED
                    }
                if (claim.reason != RejectReason.AWAITING_APPROVAL) {
                    eventLog.warn("Response read refused: ${claim.reason.name.lowercase()}")
                    status.recordAuth(AuthOutcome.REJECTED, "-", claim.reason.name.lowercase())
                }
                respond(device, requestId, gattStatus, offset, true)
            }

            is ChallengeSessions.Result.Cached -> {
                respondWithBlob(device, requestId, offset, claim.signature)
            }

            is ChallengeSessions.Result.Sign -> {
                val pending = claim.pending
                val tag = challengeTag(pending.request.rawPayload)
                val signature =
                    runCatching { signer.sign(pending.request.rawPayload) }
                        .onFailure {
                            sessions.discard(pending)
                            eventLog.error("Signing failed for $tag: ${it.javaClass.simpleName}")
                            status.recordAuth(AuthOutcome.ERROR, tag, "Signing failed")
                        }.getOrNull()
                if (signature == null) {
                    respond(device, requestId, GattStatus.REJECTED, offset, true)
                    return
                }
                sessions.attachSignature(pending, signature)
                eventLog.info("Signed challenge $tag (${signature.size} bytes)")
                status.recordAuth(AuthOutcome.SUCCESS, tag, "Challenge signed")
                respondWithBlob(device, requestId, offset, signature)
            }
        }
    }

    private fun handlePairingWrite(
        device: BluetoothDevice,
        requestId: Int,
        value: ByteArray,
        responseNeeded: Boolean,
    ) {
        val parsed = PairingCodec.parseClaim(value)
        if (parsed is ParseResult.Invalid) {
            reject(device, requestId, responseNeeded, parsed.reason, "malformed pairing claim")
            return
        }
        val claim = (parsed as ParseResult.Valid).value
        val now = System.currentTimeMillis()
        val refused = pairingCoordinator.acceptClaim(claim, now)
        if (refused != null) {
            reject(device, requestId, responseNeeded, refused, "pairing claim")
            return
        }

        val ctx = contextProvider()
        val identity =
            runCatching { signer.identity() }
                .onFailure { eventLog.error("Could not read key identity: ${it.javaClass.simpleName}") }
                .getOrNull()
        if (identity == null) {
            respond(device, requestId, GattStatus.REJECTED, 0, responseNeeded)
            return
        }

        stagedPairingResponse =
            PairingCodec.buildResponse(
                deviceId = ctx.deviceId,
                deviceName = ctx.deviceName,
                publicKeyDer = identity.publicKeyDer,
            )
        eventLog.info("Pairing claim accepted; identity staged for read")
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, responseNeeded)
    }

    private fun handlePairingRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
    ) {
        val staged = stagedPairingResponse
        if (staged == null) {
            respond(device, requestId, GattStatus.REJECTED, offset, true)
            return
        }
        val sent = respondWithBlob(device, requestId, offset, staged)
        if (!sent) return

        // Commit on the first read rather than trying to detect the last blob fragment: the
        // fragment count depends on the negotiated MTU, which we do not control. Whoever gets
        // offset 0 has already presented a valid token, so it is the right moment to commit.
        // The staged blob stays available for read-blob continuations until disconnect, but
        // the window is now Claimed so no second central can start a fresh claim.
        if (offset == 0) {
            val invite = pairingCoordinator.markClaimed(System.currentTimeMillis())
            if (invite != null) {
                listener.onPaired(invite.macInstallationId, invite.macName)
                eventLog.info("Paired with ${invite.macName}")
            }
        }
    }

    /** Serves [payload] honouring the ATT read offset. Returns false if the offset was bad. */
    private fun respondWithBlob(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        payload: ByteArray,
    ): Boolean {
        if (offset < 0 || offset > payload.size) {
            respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, true)
            return false
        }
        respond(
            device = device,
            requestId = requestId,
            gattStatus = BluetoothGatt.GATT_SUCCESS,
            offset = offset,
            responseNeeded = true,
            value = payload.copyOfRange(offset, payload.size),
        )
        return true
    }

    private fun reject(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        reason: RejectReason,
        what: String,
    ) {
        eventLog.warn("Rejected $what: ${reason.name.lowercase()}")
        respond(device, requestId, GattStatus.REJECTED, 0, responseNeeded)
    }

    @SuppressLint("MissingPermission")
    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        gattStatus: Int,
        offset: Int,
        responseNeeded: Boolean,
        value: ByteArray? = null,
    ) {
        if (!responseNeeded) return
        val server = gattServer ?: return
        runCatching { server.sendResponse(device, requestId, gattStatus, offset, value) }
            .onFailure { Log.w(TAG, "sendResponse threw", it) }
    }

    private companion object {
        const val TAG = "GattServer"
    }
}
