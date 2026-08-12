import Foundation
import Combine

/// Paired Android device identity and cryptographic public key.
struct PairedDevice: Codable, Equatable {
    let deviceId: String
    let name: String
    let publicKeyDER: Data
    let pairedAt: Date
}

/// Pairing session token representation for QR code exchange.
struct PairingQRCodePayload: Codable {
    let v: Int
    let macInstallationId: String
    let token: String
    let exp: Int64
    let name: String
}

/// Manages Mac installation identity and paired Android device records.
final class PairingManager: ObservableObject {

    private let userDefaultsKey = "com.karloyu.macbleunlock.pairedDevice"
    private let macInstallationIdKey = "com.karloyu.macbleunlock.macInstallationId"

    /// Persistent installation UUID for this Mac instance.
    let macInstallationId: String

    /// Currently active pairing token for BLE pairing exchange.
    @Published private(set) var activePairingToken: String?

    /// Stable active QR payload JSON string.
    @Published private(set) var activeQRPayloadString: String?

    /// Every Android device authorised to unlock this Mac — typically a phone and a watch.
    ///
    /// A set rather than one device so a watch can hold its own key and unlock with the phone out
    /// of range, and so either can be revoked without disturbing the other. The BLE address is
    /// never identity, so which of these is on the other end of a connection is decided by which
    /// public key verifies the signature, not by anything observed about the peripheral.
    @Published private(set) var pairedDevices: [PairedDevice] = []

    private var sessionExpiresAtDate: Date?

    var isPaired: Bool {
        !pairedDevices.isEmpty
    }

    /// Looks a device up by the identifier it claims.
    ///
    /// Compared case-insensitively to match `ChallengeCodec`, which does the same because Swift
    /// uppercases UUIDs and Android does not.
    func device(withId deviceId: String) -> PairedDevice? {
        pairedDevices.first { $0.deviceId.caseInsensitiveCompare(deviceId) == .orderedSame }
    }

    init() {
        // Retrieve or generate persistent macInstallationId
        if let existingId = UserDefaults.standard.string(forKey: macInstallationIdKey) {
            self.macInstallationId = existingId
        } else {
            let newId = UUID().uuidString
            UserDefaults.standard.set(newId, forKey: macInstallationIdKey)
            self.macInstallationId = newId
        }

        // Load saved paired devices securely from Keychain
        let stored = KeychainManager.getPairedDevices()
        if !stored.isEmpty {
            self.pairedDevices = stored
        } else if let data = UserDefaults.standard.data(forKey: userDefaultsKey),
                  let legacyDevice = try? JSONDecoder().decode(PairedDevice.self, from: data) {
            // Migrate legacy record from UserDefaults to Keychain
            KeychainManager.savePairedDevices([legacyDevice])
            UserDefaults.standard.removeObject(forKey: userDefaultsKey)
            self.pairedDevices = [legacyDevice]
        }

        // Logged unconditionally: a Keychain read that quietly returns nothing is
        // indistinguishable from never having paired, and that difference matters after a change
        // to the stored format.
        if pairedDevices.isEmpty {
            EventLogger.shared.info(category: "Pairing", "No paired devices loaded")
        } else {
            let names = pairedDevices.map(\.name).joined(separator: ", ")
            EventLogger.shared.info(
                category: "Pairing",
                "Loaded \(pairedDevices.count) paired device(s): \(names)"
            )
        }
    }

    /// Starts or retrieves a stable, unexpired pairing session and QR payload string.
    func startPairingSession(validForSeconds: TimeInterval = 300) -> String? {
        if activePairingToken != nil,
           let existingPayload = activeQRPayloadString,
           let expiresAt = sessionExpiresAtDate,
           Date() < expiresAt {
            return existingPayload
        }

        let token = ProtocolCodec.base64UrlEncode(CryptoManager.generateRandomBytes(count: 16) ?? Data())
        let expiresAtDate = Date().addingTimeInterval(validForSeconds)
        let expiresAtMs = Int64(expiresAtDate.timeIntervalSince1970 * 1000)
        let macName = Host.current().localizedName ?? "Mac"

        let payload = PairingQRCodePayload(
            v: 1,
            macInstallationId: macInstallationId,
            token: token,
            exp: expiresAtMs,
            name: macName
        )

        guard let data = try? JSONEncoder().encode(payload),
              let jsonString = String(data: data, encoding: .utf8) else {
            return nil
        }

        self.activePairingToken = token
        self.activeQRPayloadString = jsonString
        self.sessionExpiresAtDate = expiresAtDate

        EventLogger.shared.info(category: "Pairing", "Minted stable pairing session token for 300s window")
        return jsonString
    }

    /// Cancels active pairing session.
    func cancelPairingSession() {
        activePairingToken = nil
        activeQRPayloadString = nil
        sessionExpiresAtDate = nil
    }

    /// Stores a new paired Android device record securely in Keychain.
    func pair(deviceId: String, name: String, publicKeyDER: Data) {
        // Validate deviceId is valid UUID string format
        guard UUID(uuidString: deviceId) != nil else {
            EventLogger.shared.error(category: "Pairing", "Pairing rejected: deviceId '\(deviceId)' is not a valid UUID")
            return
        }

        let record = PairedDevice(
            deviceId: deviceId,
            name: name,
            publicKeyDER: publicKeyDER,
            pairedAt: Date()
        )

        // Re-pairing a device the Mac already knows replaces its record rather than adding a
        // second one: the phone mints a fresh identity key when it is unpaired and paired again,
        // so keeping the old entry would leave a key nothing can sign for.
        var updated = pairedDevices.filter {
            $0.deviceId.caseInsensitiveCompare(deviceId) != .orderedSame
        }
        let replaced = updated.count != pairedDevices.count
        updated.append(record)

        if KeychainManager.savePairedDevices(updated) {
            DispatchQueue.main.async { [weak self] in
                self?.pairedDevices = updated
                self?.cancelPairingSession()
            }
            EventLogger.shared.success(
                category: "Pairing",
                "\(replaced ? "Re-paired" : "Paired") with '\(name)' (\(deviceId)); \(updated.count) device(s) authorised"
            )
        }
    }

    /// Removes one paired device, leaving the others authorised.
    func unpair(deviceId: String) {
        guard let removed = device(withId: deviceId) else { return }
        let updated = pairedDevices.filter {
            $0.deviceId.caseInsensitiveCompare(deviceId) != .orderedSame
        }
        guard KeychainManager.savePairedDevices(updated) else { return }
        DispatchQueue.main.async { [weak self] in
            self?.pairedDevices = updated
        }
        EventLogger.shared.info(
            category: "Pairing",
            "Forgot device '\(removed.name)'; \(updated.count) device(s) still authorised"
        )
    }

    /// Removes every paired device record from Keychain.
    func unpairAll() {
        let count = pairedDevices.count
        KeychainManager.deletePairedDevices()
        UserDefaults.standard.removeObject(forKey: userDefaultsKey)
        DispatchQueue.main.async { [weak self] in
            self?.pairedDevices = []
            self?.cancelPairingSession()
        }
        EventLogger.shared.info(category: "Pairing", "Forgot all \(count) paired device(s)")
    }
}
