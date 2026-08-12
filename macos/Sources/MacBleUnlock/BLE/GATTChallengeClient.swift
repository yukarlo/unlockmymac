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
        // `sessionAlreadyInProgress` is not a failure at all — the caller treats it as a no-op
        // before it reaches any backoff. `deniedByUser` is a deliberate decision and must not
        // get the short retry, or denying produces a fresh prompt seconds later.
        case .sessionAlreadyInProgress, .deniedByUser,
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

    /// Fires if `didConnect` never arrives. See `armConnectWatchdog`.
    private var connectWatchdog: DispatchWorkItem?
    private var connectAttempt = 0

    /// True between cancelling a stalled connect and issuing the retry, so the resulting
    /// disconnect/failToConnect callbacks are not mistaken for a genuine failure.
    private var isRetryingConnect = false

    /// How long to wait for `didConnect` before assuming the address is stale.
    ///
    /// Measured establishment varies a lot — 670 ms to 2130 ms — because the central has to
    /// catch an advertising event and Low Power mode advertises only once a second. 4 s clears
    /// the observed worst case with margin; going tighter cancels connections that were about
    /// to succeed, which is worse than the stall being fixed.
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

    init(bleCentral: BLECentralManager, pairingManager: PairingManager) {
        self.bleCentral = bleCentral
        self.pairingManager = pairingManager
        super.init()
    }

    /// Performs a full GATT authentication handshake against `peripheral` using paired device credentials.
    func authenticate(
        peripheral: CBPeripheral,
        macInstallationId: String,
        pairedDevice: PairedDevice,
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
                deviceId: pairedDevice.deviceId,
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
            self.log.notice("Connecting to \(peripheral.identifier.uuidString, privacy: .public) for GATT authentication")
            EventLogger.shared.info(category: "GATT", "Initiating challenge handshake with \(pairedDevice.name)")
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

            guard self.connectAttempt < Self.maxConnectAttempts else {
                self.log.notice("Connect stalled after \(self.connectAttempt) attempts; giving up")
                EventLogger.shared.warning(
                    category: "GATT",
                    "Could not establish a connection after \(self.connectAttempt) attempts"
                )
                // A connect that never completes and never fails is the signature of a link
                // bluetoothd is holding on our behalf. Clear it so the next attempt is not
                // fighting the same corpse — this is what kept the app wedged for 11 hours.
                self.bleCentral.reclaimSystemConnections()
                self.finish(.failure(.connectionFailed(nil)), for: peripheral, disconnect: true)
                return
            }

            self.isRetryingConnect = true
            self.bleCentral.cancelConnection(peripheral)

            self.connectAttempt += 1
            self.log.notice("Connect stalled; retry \(self.connectAttempt) of \(Self.maxConnectAttempts)")
            EventLogger.shared.info(category: "GATT", "Connection stalled, retrying")
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

    /// How long to wait before asking the phone again whether the user has decided.
    ///
    /// The phone cannot volunteer the answer — the response characteristic is read-only, so the
    /// Mac only learns of an approval on its next poll, and that poll interval is added directly
    /// to the delay the user feels after tapping Approve. Polling quickly at first collapses
    /// that; nearly every approval lands within a few seconds of the prompt appearing.
    ///
    /// The slow tail matters because the approval window is a full minute: a prompt left
    /// unanswered on the lock screen would otherwise cost 240 pointless round trips, each one
    /// waking the phone's GATT server for an answer that is not ready.
    private func approvalReadDelay() -> TimeInterval {
        guard let since = approvalPendingSince,
              Date().timeIntervalSince(since) < Self.approvalFastPollWindow else {
            return Self.approvalSlowPollInterval
        }
        return Self.approvalFastPollInterval
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
        connectAttempt = 0
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
        log.notice("Connected, discovering unlock service")
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
        log.notice("Writing structured challenge payload")
        peripheral.writeValue(payload, for: challengeCharacteristic, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral, characteristic.uuid == BLEProtocol.challengeCharacteristicUUID else { return }
        if let error {
            finish(.failure(.writeFailed(error)), for: peripheral, disconnect: true)
            return
        }
        guard let responseCharacteristic else {
            finish(.failure(.characteristicNotFound), for: peripheral, disconnect: true)
            return
        }
        log.notice("Challenge payload written, reading response signature")
        peripheral.readValue(for: responseCharacteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral, characteristic.uuid == BLEProtocol.responseCharacteristicUUID else { return }
        if let error {
            let nsError = error as NSError
            // 0x80 (128) is GATT status PENDING_APPROVAL returned when "Approve every request" is enabled on Android
            if (nsError.domain == CBATTErrorDomain || nsError.domain.contains("ATT")) && nsError.code == 128 {
                EventLogger.shared.info(category: "Unlock", "Waiting for you to approve on your phone…")

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
                log.notice("Phone is waiting for the user to approve; will re-read in \(Int(delay * 1000))ms (ATT 0x80)")

                bleCentral.queue.asyncAfter(deadline: .now() + delay) { [weak self] in
                    guard let self, self.activePeripheral === peripheral else { return }
                    peripheral.readValue(for: characteristic)
                }
                return
            }

            // 0x82 (130) is DENIED — the user tapped Deny. Distinct from the opaque 0x81 so we
            // can back off properly instead of re-challenging and raising another prompt.
            if (nsError.domain == CBATTErrorDomain || nsError.domain.contains("ATT")) && nsError.code == 130 {
                log.notice("Phone reported the request was denied by the user (ATT 0x82)")
                EventLogger.shared.warning(category: "Unlock", "You denied this unlock on your phone — the Mac will not ask again for 2 minutes")
                finish(.failure(.deniedByUser), for: peripheral, disconnect: true)
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

        log.notice("Received \(signatureData.count)-byte ECDSA signature, verifying...")

        // Retrieve paired device public key DER
        DispatchQueue.main.async { [weak self] in
            guard let self, let pairedDevice = self.pairingManager.pairedDevice else {
                self?.finish(.failure(.missingPairingData), for: peripheral, disconnect: true)
                return
            }

            let isValid = CryptoManager.verifySignature(
                signatureData: signatureData,
                messageData: payload,
                publicKeyDER: pairedDevice.publicKeyDER
            )

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
