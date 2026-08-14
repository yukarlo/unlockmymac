package com.yukarlo.unlockmymac.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.yukarlo.unlockmymac.crypto.KeystoreSigner
import com.yukarlo.unlockmymac.data.AuthOutcome
import com.yukarlo.unlockmymac.data.BleStatusRepository
import com.yukarlo.unlockmymac.data.EventLog
import com.yukarlo.unlockmymac.pairing.EnrolmentCoordinator
import com.yukarlo.unlockmymac.pairing.PairingCoordinator
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.util.challengeTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * A parked challenge is gone — expired, or its connection dropped.
     *
     * The notification must be withdrawn: a prompt whose challenge no longer exists does
     * nothing when tapped, and the user is left believing they approved an unlock that never
     * happened.
     */
    fun onApprovalNoLongerValid()

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

    /**
     * A central opened an LE link, whether or not it has touched this service yet.
     *
     * Separate from [onConnectedCentralsChanged] because the count is earned by *using* the
     * service, which happens strictly after the link comes up — and the controller has already
     * stopped advertising by then. Anything that needs to react to the radio going quiet has to
     * hang off this, not off the count.
     */
    fun onCentralLinkEstablished()
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
    private val enrolmentCoordinator: EnrolmentCoordinator,
    private val status: BleStatusRepository,
    private val eventLog: EventLog,
    private val contextProvider: () -> GattContext,
    private val listener: GattServerListener,
) {
    private var gattServer: BluetoothGattServer? = null

    /**
     * Who counts as connected, and whether that is worth reporting. See [ConnectedCentrals].
     *
     * Lives in its own class so the two decisions that have gone wrong here — stranding a stale
     * address, and swallowing a link that came and went — are reachable from a unit test without a
     * `Context` or a radio.
     */
    private val centrals = ConnectedCentrals()

    /**
     * Addresses that have subscribed to [BleUuids.RESPONSE] notifications.
     *
     * Only ever consulted to decide whether pushing is possible; the read path stays available either
     * way, so an unsubscribed central is not a failure.
     */
    private val subscribers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Counts a device once it has actually used this service.
     *
     * The ACL-level connect callback says nothing about intent — everything the phone connects to
     * over LE arrives there. A read or write against one of our characteristics does not, so that is
     * what earns a place in the count the UI shows.
     */
    private fun noteEngagement(device: BluetoothDevice) {
        if (centrals.add(device.address)) publishConnectedCentrals(force = true)
    }

    /**
     * Republishes the central count, reconciled against what the Bluetooth stack actually holds.
     *
     * `getConnectedDevices(GATT_SERVER)` is the stack's own answer, so a dropped callback self-heals
     * on the next connection change or sweep instead of persisting. Falling back to the local set
     * when the manager is unavailable keeps this no worse than before.
     *
     * @param force notify the listener even if the count has not moved. Required from every
     *   connection change; must stay off for the periodic sweep. Both reasons are on
     *   [ConnectedCentrals.countToPublish].
     */
    @SuppressLint("MissingPermission") // Reading connection state needs no permission we lack.
    fun publishConnectedCentrals(force: Boolean = false) {
        val live =
            runCatching {
                context
                    .getSystemService(BluetoothManager::class.java)
                    ?.getConnectedDevices(BluetoothProfile.GATT_SERVER)
                    ?.map { it.address }
                    ?.toSet()
            }.getOrNull()

        val dropped = centrals.reconcile(live)
        if (dropped.isNotEmpty()) {
            eventLog.info("Dropping ${dropped.size} central(s) the Bluetooth stack no longer holds")
        }

        status.setConnectedCentrals(centrals.count)
        centrals.countToPublish(force)?.let(listener::onConnectedCentralsChanged)
    }

    /** Pairing identity blob, staged on a valid claim write and served on the following read. */
    @Volatile
    private var stagedPairingResponse: ByteArray? = null

    val isOpen: Boolean get() = gattServer != null

    /**
     * Serialises open and close.
     *
     * `open()` became suspending so the `openGattServer` IPC would leave the main thread, but
     * `applyState()` is launched from four separate coroutines — initial setup, the settings
     * collector, the pairing collector and the Bluetooth receiver — and two of them could pass
     * the `gattServer != null` check before either assigned. That registered a *second* GATT
     * server on the same service: the Mac's challenge write landed on one, so a prompt appeared
     * and was approved, while the response read went to the other, which held no session and
     * never answered. Every unlock timed out with the approval already given.
     *
     * Observed as two `serverIf` values from a single pid where earlier builds had one.
     */
    private val lifecycleMutex = Mutex()

    @SuppressLint("MissingPermission") // Callers check BlePermissions.hasBleAccess first.
    suspend fun open(): Boolean =
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                if (gattServer != null) return@withContext true
                if (!BlePermissions.hasBleAccess(context)) {
                    eventLog.warn("Cannot open GATT server: Bluetooth permissions not granted")
                    return@withContext false
                }
                val manager = context.getSystemService(BluetoothManager::class.java) ?: return@withContext false
                val server = manager.openGattServer(context, callback)
                if (server == null) {
                    eventLog.error("openGattServer returned null")
                    return@withContext false
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
                    // NOTIFY alongside READ. Read alone means the Mac can only learn of an approval on
                    // its next poll, so it polls every 250ms for up to a minute — and if those reads
                    // stop being delivered while the link stays up, the approval is stranded and the
                    // unlock dies with the user having already tapped Approve. Measured three times.
                    //
                    // READ stays: it is the fallback when the central does not subscribe, and it is
                    // still what serves ATT blob continuations for a signature larger than one MTU.
                    BluetoothGattCharacteristic(
                        BleUuids.RESPONSE,
                        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ,
                    ).apply {
                        addDescriptor(
                            BluetoothGattDescriptor(
                                BleUuids.CLIENT_CHARACTERISTIC_CONFIG,
                                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                            ),
                        )
                    },
                )
                service.addCharacteristic(
                    BluetoothGattCharacteristic(
                        BleUuids.PAIRING,
                        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ,
                    ),
                )
                service.addCharacteristic(
                    BluetoothGattCharacteristic(
                        BleUuids.ENROLMENT,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ,
                    ),
                )

                if (!server.addService(service)) {
                    eventLog.error("Could not add GATT service")
                    close()
                    return@withContext false
                }
                eventLog.info("GATT server open")
                true
            }
        }

    @SuppressLint("MissingPermission") // Closing is safe even if permission was revoked.
    suspend fun close() =
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                val server = gattServer ?: return@withContext
                gattServer = null
                stagedPairingResponse = null
                centrals.clear()
                sessions.clear()
                status.setConnectedCentrals(0)
                runCatching { server.close() }.onFailure { Log.w(TAG, "GATT server close threw", it) }
                eventLog.info("GATT server closed")
            }
        }

    /**
     * Hangs up on every connected central.
     *
     * Used by the manual reset. A central that has gone away without a clean disconnect leaves
     * the link half-open on one or both sides; the Mac then cannot reconnect and no challenge
     * ever arrives, while both apps still report themselves healthy. Dropping the link forces
     * the stack on both ends to start over.
     */
    @SuppressLint("MissingPermission") // Same permission as opening the server.
    fun disconnectAllCentrals(): Int {
        val server = gattServer ?: return 0
        val devices = centrals.snapshot()
        for (address in devices) {
            val device = runCatching { adapterFor(address) }.getOrNull() ?: continue
            runCatching { server.cancelConnection(device) }
                .onFailure { Log.w(TAG, "cancelConnection threw", it) }
        }
        centrals.clear()
        status.setConnectedCentrals(0)
        return devices.size
    }

    private fun adapterFor(address: String): BluetoothDevice? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)

    /** Approves or denies a parked challenge. Called from the UI or the notification action. */
    fun resolveApproval(
        id: Long,
        approved: Boolean,
    ) {
        val pending = sessions.setApproval(id, approved)
        if (pending == null) {
            // The challenge expired or its connection dropped before the user tapped. Say so —
            // silently doing nothing is what made this look like the unlock had hung.
            eventLog.warn("Approval arrived too late; that request is no longer valid")
            listener.onApprovalNoLongerValid()
            return
        }
        val tag = challengeTag(pending.request.rawPayload)
        if (approved) {
            eventLog.info("Challenge $tag approved by user")
        } else {
            eventLog.warn("Challenge $tag denied by user")
            status.recordAuth(AuthOutcome.DENIED, tag, "Denied by user")
        }
        status.setPendingApproval(null)
        listener.onApprovalNoLongerValid()
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
                        // Deliberately not counted here. This callback fires for *any* LE ACL
                        // connection to the phone, not only for peers that touch our service, so a
                        // smart ring talking to its own app was reported as a connected Mac —
                        // observed as one `connected=true` for it with no matching `false`, which
                        // pinned the count at 1 forever. Membership is earned by using the service;
                        // see [noteEngagement].
                        //
                        // The radio does stop advertising here though, whoever the peer is, so this
                        // has to be reported even when the count stays at zero.
                        listener.onCentralLinkEstablished()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        centrals.remove(key)
                        // Everything tied to this connection dies with it: no cross-connection reuse.
                        val hadPendingApproval =
                            sessions.current(key)?.approval == ApprovalState.PENDING
                        sessions.remove(key)
                        stagedPairingResponse = null
                        // The Mac is gone, so nothing will ever read the signature. Withdraw the
                        // prompt rather than leave a button that silently does nothing.
                        if (hadPendingApproval) listener.onApprovalNoLongerValid()
                    }
                }
                // Forced on disconnect only. A link that came and went without ever touching this
                // service leaves the count at zero the whole way through, so the gate hid the drop
                // and nothing restarted the advertisement the controller had already stopped.
                //
                // Not forced on connect: the restart is what the listener does with a zero count, and
                // firing it while a link is up would raise a fresh advertisement — with a fresh
                // resolvable address — in the middle of the handshake. Pausing on connect is
                // [GattServerListener.onCentralLinkEstablished]'s job instead.
                publishConnectedCentrals(force = newState == BluetoothProfile.STATE_DISCONNECTED)
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                if (descriptor.uuid != BleUuids.CLIENT_CHARACTERISTIC_CONFIG) {
                    if (responseNeeded) respond(device, requestId, GattStatus.REJECTED, offset, true)
                    return
                }
                // Subscribing is engagement: it is a write against our service, so it earns the same
                // place in the count that a challenge write does.
                noteEngagement(device)

                val enabling = value != null && value.isNotEmpty() && value[0].toInt() != 0
                if (enabling) subscribers.add(device.address) else subscribers.remove(device.address)
                eventLog.info("Mac ${if (enabling) "subscribed to" else "unsubscribed from"} response notifications")
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, true)
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
                noteEngagement(device)
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
                noteEngagement(device)
                when (characteristic.uuid) {
                    BleUuids.RESPONSE -> handleResponseRead(device, requestId, offset)
                    BleUuids.PAIRING -> handlePairingRead(device, requestId, offset)
                    BleUuids.ENROLMENT -> handleEnrolmentRead(device, requestId, offset)
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
                    when (claim.reason) {
                        RejectReason.AWAITING_APPROVAL -> GattStatus.PENDING_APPROVAL

                        // Tell the Mac this was a deliberate "no" so it stops re-challenging.
                        RejectReason.DENIED_BY_USER -> GattStatus.DENIED

                        else -> GattStatus.REJECTED
                    }
                if (claim.reason != RejectReason.AWAITING_APPROVAL) {
                    eventLog.warn("Response read refused: ${claim.reason.name.lowercase()}")
                    status.recordAuth(AuthOutcome.REJECTED, "-", claim.reason.name.lowercase())
                    // An expired challenge can still have a prompt on screen. Withdraw it.
                    if (claim.reason == RejectReason.EXPIRED) listener.onApprovalNoLongerValid()
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

    /**
     * Serves the signed offer vouching for another device, if one is currently staged.
     *
     * Nothing here decides anything: this device already made its statement when it signed the
     * offer, and the Mac is the one that checks the signature against a key it already trusts.
     * The usual answer is [GattStatus.REJECTED], which simply means "nothing to enrol" — opaque
     * like every other refusal, so a stranger reading this learns nothing about the device.
     */
    private fun handleEnrolmentRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
    ) {
        val offer = enrolmentCoordinator.current(System.currentTimeMillis())
        if (offer == null) {
            respond(device, requestId, GattStatus.REJECTED, offset, true)
            return
        }
        val sent = respondWithBlob(device, requestId, offset, offer.offerBytes)
        if (!sent) return

        if (offset == 0) {
            eventLog.info("Served an enrolment offer for '${offer.deviceName}'")
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
