import CoreBluetooth
import Foundation
import os

/// Reasons a GATT challenge round trip or verification can fail.
enum GATTChallengeError: Error, CustomStringConvertible {
    case sessionAlreadyInProgress
    case timedOut
    case connectionFailed(Error?)
    case disconnected(Error?)
    case serviceDiscoveryFailed(Error?)
    case serviceNotFound
    case characteristicDiscoveryFailed(Error?)
    case characteristicNotFound
    case randomBytesUnavailable
    case writeFailed(Error?)
    case readFailed(Error?)
    case emptyResponse
    case invalidSignature
    case missingPairingData

    /// The user tapped Deny on the phone. A decision, not a fault — do not retry promptly.
    case deniedByUser

    /// The peripheral refused the challenge with the deliberately opaque `0x81`.
    ///
    /// Opaque by design, so this covers several causes — replay, clock skew, another central
    /// mid-session — but the expected one is simply that this device is not paired with this Mac.
    ///
    /// Treated as security-relevant, so it takes the long backoff, and the refusing handle is put on
    /// cooldown in `PresenceStateMachine` so the next cycle tries a different candidate. It is not
    /// forgotten: the address is live and reachable, it just will not answer us.
    case rejectedByPeer

    /// The user explicitly refused. Warrants a long backoff, not a prompt retry.
    var isDenial: Bool {
        if case .deniedByUser = self { return true }
        return false
    }

    /// True for link-level problems that say nothing about the peer's identity.
    ///
    /// These are routine — Android rotates its private address on every `startAdvertising`, so
    /// stale handles and stalled connects are expected. They warrant a short retry backoff.
    /// Anything else (above all `invalidSignature`) is treated as security-relevant and keeps
    /// the long backoff.
    var isTransportLevel: Bool {
        switch self {
        case .timedOut, .connectionFailed, .disconnected,
             .serviceDiscoveryFailed, .serviceNotFound, .characteristicDiscoveryFailed,
             .characteristicNotFound, .writeFailed, .readFailed, .emptyResponse:
            return true
        // `sessionAlreadyInProgress` is not a failure at all — the caller treats it as a no-op
        // before it reaches any backoff. Listed here only so this switch stays exhaustive.
        // `deniedByUser` is a deliberate decision and must not get the short retry, or denying
        // produces a fresh prompt seconds later.
        // `rejectedByPeer` takes the long backoff and additionally puts the refusing handle on
        // cooldown, so the next attempt picks a different candidate rather than the same one.
        case .sessionAlreadyInProgress, .deniedByUser, .rejectedByPeer,
             .randomBytesUnavailable, .invalidSignature, .missingPairingData:
            return false
        }
    }

    var description: String {
        switch self {
        case .sessionAlreadyInProgress:
            return "Another authentication session is already in progress"
        case .timedOut:
            return "Timed out waiting for a response"
        case .connectionFailed(let error):
            return "Connection failed: \(error?.localizedDescription ?? "unknown error")"
        case .disconnected(let error):
            return "Disconnected: \(error?.localizedDescription ?? "peer closed the connection")"
        case .serviceDiscoveryFailed(let error):
            return "Service discovery failed: \(error?.localizedDescription ?? "unknown error")"
        case .serviceNotFound:
            return "Unlock service not found on peripheral"
        case .characteristicDiscoveryFailed(let error):
            return "Characteristic discovery failed: \(error?.localizedDescription ?? "unknown error")"
        case .characteristicNotFound:
            return "Expected challenge/response characteristics not found"
        case .randomBytesUnavailable:
            return "Could not generate a random challenge"
        case .writeFailed(let error):
            return "Challenge write failed: \(error?.localizedDescription ?? "unknown error")"
        case .readFailed(let error):
            return "Response read failed: \(error?.localizedDescription ?? "unknown error")"
        case .emptyResponse:
            return "Peripheral returned an empty response"
        case .invalidSignature:
            return "Cryptographic signature verification failed"
        case .missingPairingData:
            return "No paired Android device key available for verification"
        case .deniedByUser:
            return "Unlock denied on the phone"
        case .rejectedByPeer:
            return "The device refused the challenge"
        }
    }
}

/// Performs GATT connect → write structured challenge → read signature response → verify signature.
final class GATTChallengeClient: NSObject {

    typealias Completion = (Result<Bool, GATTChallengeError>) -> Void

    private let bleCentral: BLECentralManager
    private let pairingManager: PairingManager
    private let log = Logger(subsystem: "com.karloyu.macbleunlock", category: "GATTChallengeClient")

    private var activePeripheral: CBPeripheral?

    /// Whether a handshake is already running. Callers should check this rather than firing a
    /// request and treating the resulting `sessionAlreadyInProgress` as a failure.
    var isBusy: Bool {
        bleCentral.queue.sync { activePeripheral != nil }
    }
    private var responseCharacteristic: CBCharacteristic?
    private var activeRequestPayload: Data?
    private var completion: Completion?
    private var timeoutWorkItem: DispatchWorkItem?
    private var isApprovalPending = false

    /// When the phone first answered "waiting on the user", used to pace the re-reads.
    private var approvalPendingSince: Date?

    /// Re-issues an approval read whose reply never came back.
    ///
    /// The poll chain is driven purely by `didUpdateValueFor`: each 0x80 schedules the next read.
    /// A read that draws no callback at all — neither value nor error — therefore ends the chain
    /// permanently and silently. Observed three times in one session: the Mac stopped reading
    /// 2.5s and 6.9s in, the user tapped Approve 1.1s and 2.7s later, the phone logged
    /// "approved by user" and signed nothing, because nobody ever asked again. Nothing was logged
    /// on either side, since from each side's point of view it had done its part.
    private var approvalReadWatchdog: DispatchWorkItem?

    /// Consecutive re-issued approval reads that drew no reply. Reset by any callback.
    private var approvalReadRetries = 0

    /// Whether a `readValue` we issued is still awaiting its reply.
    ///
    /// This is what distinguishes a pushed notification from a read reply. `isNotifying` cannot: it
    /// reports whether notifications are *enabled*, and since we subscribe before writing the
    /// challenge it is true for the whole session — so it labelled every value, reads included, as
    /// "pushed". Answering "did the push actually arrive?" then took hand-correlating six poll
    /// timestamps against the arrival, twice.
    ///
    /// Conservative by construction: a push landing while a read is outstanding is attributed to the
    /// read, so this can under-count pushes but never invent one.
    private var readOutstanding = false

    /// True once the peripheral has confirmed our subscription, so an approval will be pushed.
    private var isSubscribedForPush = false

    /// Fires if `didConnect` never arrives. See `armConnectWatchdog`.
    private var connectWatchdog: DispatchWorkItem?
    private var connectAttempt = 0

    /// The `deviceId` of the paired record whose key verified the last successful signature.
    ///
    /// Read on `bleCentral.queue`. Set only on a verified signature and never cleared on failure, so
    /// it always names the device the current link belongs to — identity comes from the key that
    /// verified, never from an address or an advertised name.
    private(set) var authenticatedDeviceId: String?

    /// When the first `connect` for this session went out, so establishment can be measured.
    ///
    /// Deliberately the *first* attempt rather than the current one: a retry usually inherits the
    /// link the previous attempt was already opening, so timing from the retry reports a connect as
    /// fast when the user waited far longer. `connectWatchdogSeconds` has been re-tuned twice from
    /// recollection because this number was never recorded.
    private var connectStartedAt: Date?

    /// True between cancelling a stalled connect and issuing the retry, so the resulting
    /// disconnect/failToConnect callbacks are not mistaken for a genuine failure.
    private var isRetryingConnect = false

    /// How long to wait for `didConnect` before assuming the address is stale.
    ///
    /// Establishment cannot beat the peer's advertising interval, because the central has to catch an
    /// advertising event to open the link. Measured against a phone on Balanced (250 ms adverts):
    /// 670 ms, and twice effectively zero on an already-open link. Against a watch on Low Power
    /// (1 s adverts): ~3.0 s.
    ///
    /// Back to 4.0s from 2.5s. 2.5s was set from a 2130 ms worst case that only ever covered the
    /// phone, and the watch beat it every time: on 2026-08-14 the one watch connect that succeeded
    /// did so at ~3.0s — after this watchdog had already cancelled it, and only because the retry
    /// inherited the same in-flight link. The next session cancelled at 2.66s and 2.69s and gave up
    /// entirely, with the watch advertising healthily throughout and its GATT server never seeing a
    /// connection at all. Cancelling a connect that is still progressing cannot help: `connect` has
    /// no timeout, so left alone it would have landed.
    ///
    /// The cost of the wider budget is bounded at 8.3s for a genuinely dead handle, and the watch now
    /// defaults to Balanced (see `AdvertiseMode` on the Android side), so the common case is well
    /// under a second either way. If this needs tuning again, tune it against the establishment
    /// duration now logged on every `didConnect` rather than from memory.
    private static let connectWatchdogSeconds: TimeInterval = 4.0

    /// Initial attempt plus one retry.
    private static let maxConnectAttempts = 2

    /// Brief pause after cancelling a hung connect so the stack can clear it.
    private static let connectRetryDelaySeconds: TimeInterval = 0.3

    /// How long after the prompt appears to keep polling quickly for the user's answer.
    private static let approvalFastPollWindow: TimeInterval = 12

    /// Re-read cadence while the user is likely to be reaching for the phone.
    private static let approvalFastPollInterval: TimeInterval = 0.25

    /// Re-read cadence once a prompt looks like it has been left unanswered.
    private static let approvalSlowPollInterval: TimeInterval = 1.5

    /// Re-read cadence while subscribed: a liveness check, not a way of learning the answer.
    ///
    /// Long enough to stop competing with the push, short enough that the watchdog below still
    /// notices a stalled link within a few seconds rather than at the 60 s timeout.
    private static let approvalPushKeepaliveInterval: TimeInterval = 3.0

    /// How long past a scheduled read to wait before assuming its reply is not coming.
    ///
    /// Wide enough against the 250ms fast poll that a reply merely running late is not raced into
    /// a duplicate read, but tightened from 3s once the escalation below started colliding with
    /// the Mac's 30s lock-screen display timeout. Every healthy reply measured has arrived inside
    /// a few hundred milliseconds; nothing has ever been answered between 2s and 3s.
    private static let approvalReadWatchdogGrace: TimeInterval = 2

    /// How many unanswered re-reads before the connection itself is treated as the fault.
    ///
    /// One retry covers a single dropped reply, which is the only case re-reading can win. Beyond
    /// that the link is not carrying ATT traffic and asking again cannot help — so the second
    /// retry only spent time. Measured 21:44: escalation took 10s from delivery stopping, and by
    /// the time the replacement handshake was up the Mac's 30s lock-screen display timeout left it
    /// 2.3s to live. The approval landed 233ms before the display slept and was thrown away.
    private static let maxApprovalReadRetries = 1

    init(bleCentral: BLECentralManager, pairingManager: PairingManager) {
        self.bleCentral = bleCentral
        self.pairingManager = pairingManager
        super.init()
    }

    /// Performs a full GATT authentication handshake against `peripheral`.
    ///
    /// The challenge is addressed to whoever answers rather than to a named device. The Mac
    /// cannot tell which of its paired devices it has connected to before it asks — addresses
    /// rotate and are never identity — and guessing cost a full connect-and-write per wrong
    /// guess: with three devices paired and two of them switched off, one unlock took six
    /// attempts across 45 seconds. Identity is established afterwards, by which stored public
    /// key verifies the signature, which is the only evidence worth trusting anyway.
    func authenticate(
        peripheral: CBPeripheral,
        macInstallationId: String,
        completion: @escaping Completion
    ) {
        bleCentral.queue.async { [weak self] in
            guard let self else { return }

            guard self.activePeripheral == nil else {
                DispatchQueue.main.async { completion(.failure(.sessionAlreadyInProgress)) }
                return
            }

            guard let challengeData = CryptoManager.generateRandomBytes(count: 32) else {
                DispatchQueue.main.async { completion(.failure(.randomBytesUnavailable)) }
                return
            }

            let request = ProtocolCodec.ChallengeRequest(
                macInstallationId: macInstallationId,
                deviceId: BLEProtocol.anyDeviceId,
                issuedAtMs: Int64(Date().timeIntervalSince1970 * 1000),
                challengeData: challengeData
            )

            self.activePeripheral = peripheral
            self.activeRequestPayload = request.payloadData
            self.completion = completion
            peripheral.delegate = self
            self.bleCentral.connectionDelegate = self

            self.scheduleTimeout()
            self.connectAttempt = 1
            self.connectStartedAt = Date()
            self.log.notice("Connecting to \(peripheral.identifier.uuidString, privacy: .public) for GATT authentication")
            EventLogger.shared.info(category: "GATT", "Initiating challenge handshake")
            self.bleCentral.connect(peripheral)
            self.armConnectWatchdog(for: peripheral)
        }
    }

    /// Guards against `connect` never completing.
    ///
    /// `centralManager.connect` has no timeout by design — it waits forever. Android rotates
    /// its resolvable private address on every `startAdvertising`, and this app restarts
    /// advertising after each disconnect, so CoreBluetooth can hold a stale address resolution
    /// for a peripheral identifier that still looks current. The connect then hangs silently
    /// and the whole handshake dies on the 8s auth timeout instead of retrying.
    ///
    /// Cancelling and reconnecting forces the stack to resolve the address again, which in
    /// practice succeeds on the second attempt.
    private func armConnectWatchdog(for peripheral: CBPeripheral) {
        connectWatchdog?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.activePeripheral === peripheral else { return }
            guard peripheral.state != .connected else { return }

            let radio = self.radioContext(for: peripheral)

            guard self.connectAttempt < Self.maxConnectAttempts else {
                self.log.notice("Connect stalled after \(self.connectAttempt) attempts; giving up (\(radio, privacy: .public))")
                EventLogger.shared.warning(
                    category: "GATT",
                    "Could not establish a connection after \(self.connectAttempt) attempts (\(radio))"
                )
                // A connect that never completes and never fails is the signature of a link
                // bluetoothd is holding on our behalf. Clear it so the next attempt is not
                // fighting the same corpse — this is what kept the app wedged for 11 hours.
                //
                // Only this peripheral's link, though. `reclaimSystemConnections` cancels every
                // system-held link advertising the unlock service, so with a phone and a watch both
                // paired, giving up on one tore down a perfectly healthy link to the other.
                self.bleCentral.reclaimSystemConnection(for: peripheral)
                self.finish(.failure(.connectionFailed(nil)), for: peripheral, disconnect: true)
                return
            }

            self.isRetryingConnect = true
            self.bleCentral.cancelConnection(peripheral)

            self.connectAttempt += 1
            self.log.notice("Connect stalled; retry \(self.connectAttempt) of \(Self.maxConnectAttempts) (\(radio, privacy: .public))")
            EventLogger.shared.info(category: "GATT", "Connection stalled, retrying (\(radio))")
            self.bleCentral.queue.asyncAfter(deadline: .now() + Self.connectRetryDelaySeconds) { [weak self] in
                guard let self, self.activePeripheral === peripheral else { return }
                self.isRetryingConnect = false
                self.bleCentral.connect(peripheral)
                self.armConnectWatchdog(for: peripheral)
            }
        }
        connectWatchdog = workItem
        bleCentral.queue.asyncAfter(deadline: .now() + Self.connectWatchdogSeconds, execute: workItem)
    }

    /// Signal strength and advertisement freshness, for attaching to a stall report.
    ///
    /// A stall has two plausible causes that the log could not previously tell apart: a weak link
    /// (see the RSSI correlation — clean at -57 dBm, marginal at -66, failing at -74), or the
    /// stale address resolution described on `armConnectWatchdog`. They point at completely
    /// different fixes, so both numbers are recorded rather than just the signal.
    ///
    /// "no recent advertisement" is the interesting case: it means the peripheral has already
    /// aged out of `discoveredPeripherals`, which is direct evidence for the stale-address theory.
    ///
    /// Called from the watchdog, which runs on `bleCentral.queue`, so it reads the queue-confined
    /// mirror. `discoveredPeripherals` itself is main-queue owned — reading it here was a data race.
    private func radioContext(for peripheral: CBPeripheral) -> String {
        // How long the connect has actually been outstanding. Without it a stall report cannot be
        // told apart from a watchdog set too tight, which is exactly the mistake this file has made
        // twice.
        let waited = connectStartedAt.map { String(format: ", waited %.1fs", Date().timeIntervalSince($0)) } ?? ""
        guard let entry = bleCentral.peripheralsOnQueue()[peripheral.identifier] else {
            return "no recent advertisement\(waited)"
        }
        let age = String(format: "%.1f", Date().timeIntervalSince(entry.lastSeenAt))
        guard let rssi = entry.averageRSSI else {
            return "no RSSI yet, last advertised \(age)s ago\(waited)"
        }
        return String(format: "%.1f dBm, last advertised %@s ago%@", rssi, age, waited)
    }

    /// Aborts in-flight handshake.
    func cancel() {
        bleCentral.queue.async { [weak self] in
            guard let self, let peripheral = self.activePeripheral else { return }
            self.finish(.failure(.timedOut), for: peripheral, disconnect: true, invokeCompletion: false)
        }
    }

    private func scheduleTimeout(duration: TimeInterval = BLEProtocol.authTimeoutSeconds) {
        timeoutWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, let peripheral = self.activePeripheral else { return }
            self.log.notice("GATT round trip timed out")
            EventLogger.shared.warning(category: "GATT", "Handshake timed out")
            self.finish(.failure(.timedOut), for: peripheral, disconnect: true)
        }
        timeoutWorkItem = workItem
        bleCentral.queue.asyncAfter(deadline: .now() + duration, execute: workItem)
    }

    private func extendTimeoutForUserApproval() {
        scheduleTimeout(duration: 60)
    }

    /// How long to wait before asking the peripheral again whether the user has decided.
    ///
    /// Three cadences, because the reason for asking changed once the peripheral could push:
    ///
    /// - **Subscribed**: the answer arrives on its own, so reading is only a liveness check. Measured
    ///   2026-08-15 00:15:11, the pushed signature landed 129 ms after a poll reply and 129 ms *before*
    ///   the next read was due — the fast poll contributed nothing but 40 round trips per approval,
    ///   and each one was a second claimant that could race the push for the signature.
    /// - **Not subscribed, recently prompted**: the old fast poll. The peripheral cannot volunteer the
    ///   answer, so the interval is added directly to the delay the user feels after tapping Approve.
    /// - **Not subscribed, prompt going stale**: the slow tail, so a prompt left unanswered for the
    ///   full minute does not cost 240 pointless round trips.
    ///
    /// Polling is deliberately *not* switched off entirely when subscribed. Every answered read is
    /// proof the link still carries ATT traffic, and that is the only thing that detects the stall
    /// which lost two approvals tonight — with no reads at all, a dead link and a user who has not
    /// tapped yet look identical until the 60 s timeout.
    private func approvalReadDelay() -> TimeInterval {
        if isSubscribedForPush { return Self.approvalPushKeepaliveInterval }
        guard let since = approvalPendingSince,
              Date().timeIntervalSince(since) < Self.approvalFastPollWindow else {
            return Self.approvalSlowPollInterval
        }
        return Self.approvalFastPollInterval
    }

    /// Schedules a re-read for `delay` plus a grace period, in case the reply never arrives.
    ///
    /// Cancelled by the next callback, so in the normal case it never fires. The outer 60s
    /// approval timeout still bounds the whole wait — this only stops the poll from dying early.
    private func armApprovalReadWatchdog(
        after delay: TimeInterval,
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic
    ) {
        approvalReadWatchdog?.cancel()

        let item = DispatchWorkItem { [weak self] in
            guard let self,
                  self.activePeripheral === peripheral,
                  self.isApprovalPending else { return }

            self.approvalReadRetries += 1

            // Past this point the connection is the fault, not the read.
            //
            // Measured 20:36: the phone held the ACL open the whole time — it only saw the link
            // drop 15.6s after the Mac hung up, on the supervision timeout — yet four re-issued
            // reads never reached its GATT server at all. It had the approval and signs on read,
            // so a single delivered read would have finished the unlock. CoreBluetooth was
            // accepting `readValue` and dropping it onto a live connection carrying no ATT
            // traffic, and no amount of asking again fixes that. The user typed their password.
            //
            // `readFailed` is transport-level, so the caller takes the short backoff and
            // re-challenges rather than treating this as a refusal. Reconnecting means a new
            // challenge and a second prompt, which is worse than seamless and far better than a
            // dead end.
            guard self.approvalReadRetries <= Self.maxApprovalReadRetries else {
                self.log.notice("Approval reads unanswered after \(Self.maxApprovalReadRetries, privacy: .public) retries; dropping the link")
                EventLogger.shared.warning(
                    category: "Unlock",
                    "\(self.targetDeviceName(for: peripheral)) stopped answering — reconnecting and asking again"
                )
                self.finish(.failure(.readFailed(nil)), for: peripheral, disconnect: true)
                return
            }

            self.log.notice("""
                Approval read drew no reply; re-issuing \
                (\(self.approvalReadRetries, privacy: .public) of \(Self.maxApprovalReadRetries, privacy: .public))
                """)
            self.issueRead(peripheral, characteristic)
            self.armApprovalReadWatchdog(after: delay, peripheral: peripheral, characteristic: characteristic)
        }
        approvalReadWatchdog = item
        bleCentral.queue.asyncAfter(deadline: .now() + delay + Self.approvalReadWatchdogGrace, execute: item)
    }

    /// Every read goes through here so `readOutstanding` cannot drift out of step with reality.
    private func issueRead(_ peripheral: CBPeripheral, _ characteristic: CBCharacteristic) {
        readOutstanding = true
        peripheral.readValue(for: characteristic)
    }

    private func finish(
        _ result: Result<Bool, GATTChallengeError>,
        for peripheral: CBPeripheral,
        disconnect: Bool,
        invokeCompletion: Bool = true
    ) {
        guard activePeripheral === peripheral else { return }

        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil
        connectWatchdog?.cancel()
        connectWatchdog = nil
        approvalReadWatchdog?.cancel()
        approvalReadWatchdog = nil
        approvalReadRetries = 0
        readOutstanding = false
        isSubscribedForPush = false
        connectAttempt = 0
        connectStartedAt = nil
        isRetryingConnect = false

        let pendingCompletion = completion
        activePeripheral = nil
        responseCharacteristic = nil
        activeRequestPayload = nil
        isApprovalPending = false
        approvalPendingSince = nil
        completion = nil
        peripheral.delegate = nil

        if bleCentral.connectionDelegate === self {
            bleCentral.connectionDelegate = nil
        }

        if disconnect {
            bleCentral.cancelConnection(peripheral)
        }

        if invokeCompletion {
            DispatchQueue.main.async {
                pendingCompletion?(result)
            }
        }
    }
}

extension GATTChallengeClient: BLEPeripheralConnectionDelegate {

    func bleCentral(_ manager: BLECentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === activePeripheral else { return }
        connectWatchdog?.cancel()
        connectWatchdog = nil
        // The number `connectWatchdogSeconds` has to be set from. Measured from the first attempt, so
        // it reflects what the user waited even when a retry is what finally reported success.
        let established = connectStartedAt.map { String(format: "%.2f", Date().timeIntervalSince($0)) } ?? "?"
        log.notice("""
            Connected after \(established, privacy: .public)s \
            on attempt \(self.connectAttempt, privacy: .public); discovering unlock service
            """)
        peripheral.discoverServices([BLEProtocol.serviceUUID])
    }

    func bleCentral(_ manager: BLECentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === activePeripheral else { return }
        // Our own watchdog cancellation surfaces here; let the retry proceed.
        guard !isRetryingConnect else { return }
        EventLogger.shared.error(category: "GATT", "Connection failed: \(error?.localizedDescription ?? "unknown")")
        finish(.failure(.connectionFailed(error)), for: peripheral, disconnect: false)
    }

    func bleCentralDidInvalidateConnections(_ manager: BLECentralManager) {
        guard let peripheral = activePeripheral else { return }
        log.notice("Radio invalidated all connections; abandoning in-flight handshake")
        EventLogger.shared.info(category: "GATT", "Bluetooth went away — abandoning handshake")
        finish(.failure(.disconnected(nil)), for: peripheral, disconnect: false)
    }

    func bleCentral(_ manager: BLECentralManager, didDisconnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === activePeripheral else { return }
        // Cancelling a stalled pending connection reports as a disconnect; not a real failure.
        guard !isRetryingConnect else { return }
        finish(.failure(.disconnected(error)), for: peripheral, disconnect: false)
    }
}

extension GATTChallengeClient: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === activePeripheral else { return }
        if let error {
            finish(.failure(.serviceDiscoveryFailed(error)), for: peripheral, disconnect: true)
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == BLEProtocol.serviceUUID }) else {
            finish(.failure(.serviceNotFound), for: peripheral, disconnect: true)
            return
        }
        peripheral.discoverCharacteristics(
            [BLEProtocol.challengeCharacteristicUUID, BLEProtocol.responseCharacteristicUUID],
            for: service
        )
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === activePeripheral else { return }
        if let error {
            finish(.failure(.characteristicDiscoveryFailed(error)), for: peripheral, disconnect: true)
            return
        }
        guard
            let characteristics = service.characteristics,
            let challengeCharacteristic = characteristics.first(where: { $0.uuid == BLEProtocol.challengeCharacteristicUUID }),
            let responseCharacteristic = characteristics.first(where: { $0.uuid == BLEProtocol.responseCharacteristicUUID })
        else {
            finish(.failure(.characteristicNotFound), for: peripheral, disconnect: true)
            return
        }

        guard let payload = activeRequestPayload else {
            finish(.failure(.randomBytesUnavailable), for: peripheral, disconnect: true)
            return
        }

        self.responseCharacteristic = responseCharacteristic

        // Subscribe before writing the challenge, so a peripheral that signs immediately cannot push
        // before we are listening. Notifying is how an approval reaches us without being polled for:
        // the read path stays as the fallback, so a peripheral that does not support notify — or a
        // subscription the stack refuses — costs nothing but the old behaviour.
        if responseCharacteristic.properties.contains(.notify) {
            peripheral.setNotifyValue(true, for: responseCharacteristic)
        } else {
            log.notice("Peripheral does not offer response notifications; polling only")
        }

        log.notice("Writing structured challenge payload")
        peripheral.writeValue(payload, for: challengeCharacteristic, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral, characteristic.uuid == BLEProtocol.challengeCharacteristicUUID else { return }
        if let error {
            // A challenge addressed to a different device is refused here, on the write, not on
            // the read: the peripheral validates the payload as it arrives and never creates a
            // session. Treating that as a transport fault meant retrying the same device every
            // few seconds forever once a second device was paired.
            let nsError = error as NSError
            if (nsError.domain == CBATTErrorDomain || nsError.domain.contains("ATT")) && nsError.code == 129 {
                log.notice("Device refused the challenge on write (ATT 0x81)")
                finish(.failure(.rejectedByPeer), for: peripheral, disconnect: true)
                return
            }
            // CoreBluetooth renders every application-defined ATT code as "Unknown ATT error", so
            // the number is the only way to tell them apart in a log.
            log.notice("Challenge write failed (ATT code \(nsError.code, privacy: .public))")
            finish(.failure(.writeFailed(error)), for: peripheral, disconnect: true)
            return
        }
        guard let responseCharacteristic else {
            finish(.failure(.characteristicNotFound), for: peripheral, disconnect: true)
            return
        }
        log.notice("Challenge payload written, reading response signature")
        issueRead(peripheral, responseCharacteristic)
    }

    private func targetDeviceName(for peripheral: CBPeripheral) -> String {
        if let name = bleCentral.peripheralsOnQueue()[peripheral.identifier]?.name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return name.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let name = peripheral.name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return name.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let paired = pairingManager.pairedDevices.first(where: { $0.deviceId == peripheral.identifier.uuidString }) ?? pairingManager.pairedDevices.first {
            return paired.name
        }
        return "paired device"
    }

    /// Reports whether the subscription took, so a silent failure does not look like a slow peer.
    ///
    /// Not fatal either way: the poll below is the fallback, so a refused subscription only costs the
    /// old behaviour rather than the unlock.
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral, characteristic.uuid == BLEProtocol.responseCharacteristicUUID else { return }
        if let error {
            log.notice("Could not subscribe to response notifications, will poll instead: \(error.localizedDescription, privacy: .public)")
        } else if characteristic.isNotifying {
            isSubscribedForPush = true
            log.notice("Subscribed to response notifications; polling drops to a keepalive")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral, characteristic.uuid == BLEProtocol.responseCharacteristicUUID else { return }

        // The reply we were waiting on arrived, whatever it says. Any 0x80 below arms a fresh one.
        // Captured before clearing: the label below needs to know whether *this* value answered a
        // read we had issued, and the flag has to be cleared here for the next one.
        let answeredAReadWeIssued = readOutstanding
        readOutstanding = false
        approvalReadWatchdog?.cancel()
        approvalReadWatchdog = nil
        approvalReadRetries = 0
        if let error {
            let nsError = error as NSError
            let deviceName = targetDeviceName(for: peripheral)

            // 0x80 (128) is GATT status PENDING_APPROVAL returned when "Approve every request" is enabled on Android
            if (nsError.domain == CBATTErrorDomain || nsError.domain.contains("ATT")) && nsError.code == 128 {
                EventLogger.shared.info(category: "Unlock", "Waiting for you to approve on \(deviceName)…")

                // Arm 60s approval timeout once when approval pending is first encountered
                if !isApprovalPending {
                    isApprovalPending = true
                    approvalPendingSince = Date()
                    extendTimeoutForUserApproval()
                }

                // Computed once and used for both the log and the schedule: quoting a fixed
                // interval here while the delay was adaptive meant every early poll logged a
                // cadence six times slower than the one it actually used.
                let delay = approvalReadDelay()
                let mode = isSubscribedForPush ? "keepalive" : "poll"
                log.notice("\(deviceName, privacy: .public) is waiting for the user to approve; \(mode, privacy: .public) re-read in \(Int(delay * 1000))ms (ATT 0x80)")

                bleCentral.queue.asyncAfter(deadline: .now() + delay) { [weak self] in
                    guard let self, self.activePeripheral === peripheral else { return }
                    self.issueRead(peripheral, characteristic)
                }
                armApprovalReadWatchdog(after: delay, peripheral: peripheral, characteristic: characteristic)
                return
            }

            // 0x82 (130) is DENIED — the user tapped Deny. Distinct from the opaque 0x81 so we
            // can back off properly instead of re-challenging and raising another prompt.
            if (nsError.domain == CBATTErrorDomain || nsError.domain.contains("ATT")) && nsError.code == 130 {
                log.notice("\(deviceName, privacy: .public) reported the request was denied by the user (ATT 0x82)")
                EventLogger.shared.warning(category: "Unlock", "You denied this unlock on \(deviceName) — the Mac will not ask again for 2 minutes")
                finish(.failure(.deniedByUser), for: peripheral, disconnect: true)
                return
            }

            // 0x81 (129) is the opaque catch-all refusal. With several devices paired the ordinary
            // cause is that this challenge named a different one, so the caller re-addresses it
            // instead of treating it as a link fault and retrying the same device forever.
            if (nsError.domain == CBATTErrorDomain || nsError.domain.contains("ATT")) && nsError.code == 129 {
                log.notice("Device refused the challenge (ATT 0x81)")
                finish(.failure(.rejectedByPeer), for: peripheral, disconnect: true)
                return
            }

            finish(.failure(.readFailed(error)), for: peripheral, disconnect: true)
            return
        }
        guard let signatureData = characteristic.value, !signatureData.isEmpty else {
            finish(.failure(.emptyResponse), for: peripheral, disconnect: true)
            return
        }

        guard let payload = activeRequestPayload else {
            finish(.failure(.randomBytesUnavailable), for: peripheral, disconnect: true)
            return
        }

        // A value with no read outstanding can only have been pushed. See `readOutstanding`.
        let arrival = answeredAReadWeIssued ? "read" : "pushed"
        log.notice("Received \(signatureData.count)-byte ECDSA signature (\(arrival, privacy: .public)), verifying...")

        // Verify against every authorised device, not just the one the challenge named.
        //
        // Which device answered is decided here and nowhere else: a signature that verifies under
        // a stored public key proves the holder of that key signed these exact bytes. Trying each
        // candidate costs about a millisecond apiece — the measured P-256 verify — and means the
        // Mac never has to trust anything the peripheral says about its own identity.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let candidates = self.pairingManager.pairedDevices
            guard !candidates.isEmpty else {
                self.finish(.failure(.missingPairingData), for: peripheral, disconnect: true)
                return
            }

            let matched = candidates.first { candidate in
                CryptoManager.verifySignature(
                    signatureData: signatureData,
                    messageData: payload,
                    publicKeyDER: candidate.publicKeyDER
                )
            }
            let isValid = matched != nil
            if let matched {
                EventLogger.shared.info(category: "Auth", "Signature matched '\(matched.name)'")
                // Which paired record answered is only knowable here, from the key that verified.
                // Handing it upward means the rest of the app can attribute this link to a device
                // instead of guessing from an advertised name — the peer stops advertising while
                // connected, so for a device that advertises no name there is nothing else to go on.
                self.bleCentral.queue.async { self.authenticatedDeviceId = matched.deviceId }
            }

            self.bleCentral.queue.async {
                if isValid {
                    self.log.notice("Signature successfully verified!")
                    EventLogger.shared.success(category: "Crypto", "P-256 signature verified successfully")
                    self.finish(.success(true), for: peripheral, disconnect: true)
                } else {
                    self.log.error("Signature verification failed!")
                    EventLogger.shared.error(category: "Crypto", "Signature verification failed")
                    self.finish(.failure(.invalidSignature), for: peripheral, disconnect: true)
                }
            }
        }
    }
}
