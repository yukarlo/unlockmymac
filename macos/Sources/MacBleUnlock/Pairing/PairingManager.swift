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

    /// Currently paired Android phone, or `nil` if unpaired.
    @Published private(set) var pairedDevice: PairedDevice?

    private var sessionExpiresAtDate: Date?

    var isPaired: Bool {
        pairedDevice != nil
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

        // Load saved paired device securely from Keychain
        if let device = KeychainManager.getPairedDevice() {
            self.pairedDevice = device
        } else if let data = UserDefaults.standard.data(forKey: userDefaultsKey),
                  let legacyDevice = try? JSONDecoder().decode(PairedDevice.self, from: data) {
            // Migrate legacy record from UserDefaults to Keychain
            KeychainManager.savePairedDevice(legacyDevice)
            UserDefaults.standard.removeObject(forKey: userDefaultsKey)
            self.pairedDevice = legacyDevice
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
        if KeychainManager.savePairedDevice(record) {
            DispatchQueue.main.async { [weak self] in
                self?.pairedDevice = record
                self?.cancelPairingSession()
            }
            EventLogger.shared.success(category: "Pairing", "Successfully paired with '\(name)' (\(deviceId))")
        }
    }

    /// Removes existing paired device record from Keychain.
    func unpair() {
        let oldName = pairedDevice?.name ?? "device"
        KeychainManager.deletePairedDevice()
        UserDefaults.standard.removeObject(forKey: userDefaultsKey)
        DispatchQueue.main.async { [weak self] in
            self?.pairedDevice = nil
            self?.cancelPairingSession()
        }
        EventLogger.shared.info(category: "Pairing", "Unpaired device '\(oldName)'")
    }
}
