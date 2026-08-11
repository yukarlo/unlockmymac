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
    private var responseCharacteristic: CBCharacteristic?
    private var activeRequestPayload: Data?
    private var completion: Completion?
    private var timeoutWorkItem: DispatchWorkItem?
    private var isApprovalPending = false

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
            self.log.info("Connecting to \(peripheral.identifier.uuidString, privacy: .public) for GATT authentication")
            EventLogger.shared.info(category: "GATT", "Initiating challenge handshake with \(pairedDevice.name)")
            self.bleCentral.connect(peripheral)
        }
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
            self.log.info("GATT round trip timed out")
            EventLogger.shared.warning(category: "GATT", "Handshake timed out")
            self.finish(.failure(.timedOut), for: peripheral, disconnect: true)
        }
        timeoutWorkItem = workItem
        bleCentral.queue.asyncAfter(deadline: .now() + duration, execute: workItem)
    }

    private func extendTimeoutForUserApproval() {
        scheduleTimeout(duration: 60)
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

        let pendingCompletion = completion
        activePeripheral = nil
        responseCharacteristic = nil
        activeRequestPayload = nil
        isApprovalPending = false
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
        log.info("Connected, discovering unlock service")
        peripheral.discoverServices([BLEProtocol.serviceUUID])
    }

    func bleCentral(_ manager: BLECentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === activePeripheral else { return }
        EventLogger.shared.error(category: "GATT", "Connection failed: \(error?.localizedDescription ?? "unknown")")
        finish(.failure(.connectionFailed(error)), for: peripheral, disconnect: false)
    }

    func bleCentral(_ manager: BLECentralManager, didDisconnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === activePeripheral else { return }
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
        log.info("Writing structured challenge payload")
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
        log.info("Challenge payload written, reading response signature")
        peripheral.readValue(for: responseCharacteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral, characteristic.uuid == BLEProtocol.responseCharacteristicUUID else { return }
        if let error {
            let nsError = error as NSError
            // 0x80 (128) is GATT status PENDING_APPROVAL returned when "Approve every request" is enabled on Android
            if (nsError.domain == CBATTErrorDomain || nsError.domain.contains("ATT")) && nsError.code == 128 {
                log.info("Android returned 0x80 PENDING_APPROVAL. User approval pending on phone, re-reading in 1.5s...")
                EventLogger.shared.info(category: "GATT", "Awaiting user approval on Android phone (0x80)...")

                // Arm 60s approval timeout once when approval pending is first encountered
                if !isApprovalPending {
                    isApprovalPending = true
                    extendTimeoutForUserApproval()
                }

                bleCentral.queue.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                    guard let self, self.activePeripheral === peripheral else { return }
                    peripheral.readValue(for: characteristic)
                }
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

        log.info("Received \(signatureData.count)-byte ECDSA signature, verifying...")

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
                    self.log.info("Signature successfully verified!")
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
