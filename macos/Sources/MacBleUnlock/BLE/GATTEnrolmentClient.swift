import CoreBluetooth
import Foundation
import os

/// A signed statement from an already-trusted device vouching for another device's public key.
struct EnrolmentOffer {
    let macInstallationId: String
    let deviceId: String
    let deviceName: String
    let publicKeyDER: Data
    let issuedAtMs: Int64
    /// The exact byte range the signature covers, kept verbatim from the wire.
    let signedBytes: Data
    let signature: Data
}

enum EnrolmentError: Error, CustomStringConvertible {
    case noOfferAvailable
    case malformedOffer
    case offerForAnotherMac
    case offerExpired
    case selfEnrolment
    case notVouchedByAPairedDevice
    case alreadyPaired(String)
    case transport(Error?)

    var description: String {
        switch self {
        case .noOfferAvailable:
            return "No device is waiting to be added"
        case .malformedOffer:
            return "The offer could not be read"
        case .offerForAnotherMac:
            return "That offer was made for a different Mac"
        case .offerExpired:
            return "That offer has expired — send the key again from the watch"
        case .selfEnrolment:
            return "A device cannot authorise itself"
        case .notVouchedByAPairedDevice:
            return "The offer was not signed by a device this Mac trusts"
        case .alreadyPaired(let name):
            return "'\(name)' is already paired"
        case .transport(let error):
            return "Could not read the offer: \(error?.localizedDescription ?? "unknown error")"
        }
    }
}

/// Reads and verifies an enrolment offer from an already-paired device.
///
/// This is how a watch gets authorised without ever scanning the pairing QR: the phone, whose key
/// this Mac already stores, signs a statement naming the watch's public key, and this reads that
/// statement over the same link it uses for unlocks.
///
/// Four things must hold before a device is added, and none of them trusts the peripheral's own
/// account of itself:
///  - the signature verifies under a public key already in the Keychain;
///  - the offer names *this* Mac, so it cannot be replayed to another Mac trusting the same phone;
///  - it is inside its short lifetime;
///  - the vouching device is not naming itself, which would let a device about to be revoked
///    quietly re-authorise itself under a fresh key.
///
/// Nothing here runs on its own. The user triggers it from the pairing window, which means a human
/// is sitting at an unlocked Mac when a credential is added.
final class GATTEnrolmentClient: NSObject {

    typealias Completion = (Result<PairedDevice, EnrolmentError>) -> Void

    private static let offerPrefix = "mac-ble-enrol:v1"
    private static let signatureSeparator = "\nsignature="
    private static let bodyKeys = ["macInstallationId", "deviceId", "name", "publicKey", "issuedAt"]
    private static let publicKeyDERBytes = 91
    private static let offerTTLSeconds: TimeInterval = 300
    private static let readTimeoutSeconds: TimeInterval = 10

    private let bleCentral: BLECentralManager
    private let pairingManager: PairingManager
    private let log = Logger(subsystem: "com.karloyu.macbleunlock", category: "GATTEnrolmentClient")

    private var activePeripheral: CBPeripheral?
    private var completion: Completion?
    private var timeoutWorkItem: DispatchWorkItem?
    private var accumulated = Data()

    init(bleCentral: BLECentralManager, pairingManager: PairingManager) {
        self.bleCentral = bleCentral
        self.pairingManager = pairingManager
        super.init()
    }

    /// Connects to `peripheral` and asks whether it is vouching for anything.
    func readOffer(from peripheral: CBPeripheral, completion: @escaping Completion) {
        bleCentral.queue.async { [weak self] in
            guard let self else { return }
            guard self.activePeripheral == nil else {
                DispatchQueue.main.async { completion(.failure(.transport(nil))) }
                return
            }
            self.activePeripheral = peripheral
            self.completion = completion
            self.accumulated = Data()
            peripheral.delegate = self
            self.bleCentral.connectionDelegate = self
            self.scheduleTimeout()
            self.log.notice("Reading enrolment offer from \(peripheral.identifier.uuidString, privacy: .public)")
            self.bleCentral.connect(peripheral)
        }
    }

    private func scheduleTimeout() {
        timeoutWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            self?.finish(.failure(.transport(nil)))
        }
        timeoutWorkItem = item
        bleCentral.queue.asyncAfter(deadline: .now() + Self.readTimeoutSeconds, execute: item)
    }

    private func finish(_ result: Result<PairedDevice, EnrolmentError>) {
        guard let peripheral = activePeripheral else { return }
        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil
        let pending = completion
        completion = nil
        activePeripheral = nil
        accumulated = Data()
        peripheral.delegate = nil
        // Released like the challenge and pairing clients do. `connectionDelegate` is a single
        // slot shared by all three, so leaving it pointed here meant a later handshake's
        // connect callbacks could be delivered to a finished enrolment instead.
        if bleCentral.connectionDelegate === self {
            bleCentral.connectionDelegate = nil
        }
        bleCentral.cancelConnection(peripheral)
        DispatchQueue.main.async { pending?(result) }
    }

    // MARK: - Verification

    /// Checks an offer against everything this Mac already knows, and pairs the device if it holds.
    private func acceptIfValid(_ payload: Data) {
        guard let offer = Self.parseOffer(payload) else {
            finish(.failure(.malformedOffer))
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            guard offer.macInstallationId.caseInsensitiveCompare(self.pairingManager.macInstallationId) == .orderedSame else {
                self.bleCentral.queue.async { self.finish(.failure(.offerForAnotherMac)) }
                return
            }

            let ageSeconds = Date().timeIntervalSince1970 - Double(offer.issuedAtMs) / 1000
            guard ageSeconds < Self.offerTTLSeconds, ageSeconds > -120 else {
                self.bleCentral.queue.async { self.finish(.failure(.offerExpired)) }
                return
            }

            if let existing = self.pairingManager.device(withId: offer.deviceId) {
                self.bleCentral.queue.async { self.finish(.failure(.alreadyPaired(existing.name))) }
                return
            }

            // The signature decides who vouched. Trying each stored key is how the Mac learns
            // that without believing anything the peripheral claims about its own identity.
            let voucher = self.pairingManager.pairedDevices.first { candidate in
                CryptoManager.verifySignature(
                    signatureData: offer.signature,
                    messageData: offer.signedBytes,
                    publicKeyDER: candidate.publicKeyDER
                )
            }

            guard let voucher else {
                self.bleCentral.queue.async { self.finish(.failure(.notVouchedByAPairedDevice)) }
                return
            }

            guard voucher.deviceId.caseInsensitiveCompare(offer.deviceId) != .orderedSame else {
                self.bleCentral.queue.async { self.finish(.failure(.selfEnrolment)) }
                return
            }

            EventLogger.shared.success(
                category: "Pairing",
                "'\(voucher.name)' vouched for '\(offer.deviceName)' — adding it"
            )
            self.pairingManager.pair(
                deviceId: offer.deviceId,
                name: offer.deviceName,
                publicKeyDER: offer.publicKeyDER
            )

            let record = PairedDevice(
                deviceId: offer.deviceId,
                name: offer.deviceName,
                publicKeyDER: offer.publicKeyDER,
                pairedAt: Date()
            )
            self.bleCentral.queue.async { self.finish(.success(record)) }
        }
    }

    /// Parses the wire format without verifying anything. See `EnrolmentCodec` on the Android side.
    static func parseOffer(_ payload: Data) -> EnrolmentOffer? {
        guard !payload.isEmpty, payload.count <= 1024,
              let text = String(data: payload, encoding: .utf8) else { return nil }

        // `name` is stripped of newlines when the offer is built, so this can only be the real one.
        guard let separatorRange = text.range(of: signatureSeparator, options: .backwards) else { return nil }
        let bodyText = String(text[text.startIndex..<separatorRange.lowerBound])
        let signatureB64 = String(text[separatorRange.upperBound...])
        guard !signatureB64.isEmpty,
              signatureB64 == signatureB64.trimmingCharacters(in: .whitespacesAndNewlines),
              let signature = Data(base64Encoded: signatureB64), !signature.isEmpty else { return nil }

        let lines = bodyText.components(separatedBy: "\n")
        guard lines.count == bodyKeys.count + 1, lines[0] == offerPrefix else { return nil }
        guard lines.allSatisfy({ $0 == $0.trimmingCharacters(in: .whitespaces) }) else { return nil }

        var values: [String] = []
        for (index, key) in bodyKeys.enumerated() {
            let line = lines[index + 1]
            guard let separator = line.firstIndex(of: "="), separator != line.startIndex else { return nil }
            guard String(line[line.startIndex..<separator]) == key else { return nil }
            let value = String(line[line.index(after: separator)...])
            guard !value.isEmpty else { return nil }
            values.append(value)
        }

        guard UUID(uuidString: values[0]) != nil, UUID(uuidString: values[1]) != nil else { return nil }
        guard let publicKey = Data(base64Encoded: values[3]), publicKey.count == publicKeyDERBytes else { return nil }
        guard let issuedAt = Int64(values[4]), issuedAt > 0 else { return nil }

        return EnrolmentOffer(
            macInstallationId: values[0],
            deviceId: values[1],
            deviceName: values[2],
            publicKeyDER: publicKey,
            issuedAtMs: issuedAt,
            signedBytes: Data(bodyText.utf8),
            signature: signature
        )
    }
}

extension GATTEnrolmentClient: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === activePeripheral else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == BLEProtocol.serviceUUID }) else {
            finish(.failure(.transport(error)))
            return
        }
        peripheral.discoverCharacteristics([BLEProtocol.enrolmentCharacteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === activePeripheral else { return }
        guard error == nil,
              let characteristic = service.characteristics?.first(where: {
                  $0.uuid == BLEProtocol.enrolmentCharacteristicUUID
              }) else {
            // An older phone build has no enrolment characteristic at all.
            finish(.failure(.noOfferAvailable))
            return
        }
        peripheral.readValue(for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral,
              characteristic.uuid == BLEProtocol.enrolmentCharacteristicUUID else { return }

        if error != nil {
            // 0x81 here just means "nothing staged" — the ordinary answer.
            finish(.failure(.noOfferAvailable))
            return
        }
        guard let value = characteristic.value, !value.isEmpty else {
            finish(.failure(.noOfferAvailable))
            return
        }
        acceptIfValid(value)
    }
}

extension GATTEnrolmentClient: BLEPeripheralConnectionDelegate {
    func bleCentral(_ manager: BLECentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === activePeripheral else { return }
        peripheral.discoverServices([BLEProtocol.serviceUUID])
    }

    func bleCentral(_ manager: BLECentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === activePeripheral else { return }
        finish(.failure(.transport(error)))
    }

    func bleCentral(_ manager: BLECentralManager, didDisconnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === activePeripheral else { return }
        finish(.failure(.transport(error)))
    }

    func bleCentralDidInvalidateConnections(_ manager: BLECentralManager) {
        finish(.failure(.transport(nil)))
    }
}
