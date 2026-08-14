import Foundation
import Security

/// Keychain helper for securely storing and retrieving login credentials and paired device public keys.
enum KeychainManager {
    private static let service = "com.karloyu.macbleunlock"
    private static let accountPassword = "MacBleUnlockCredential"
    private static let accountPairedDevice = "MacBleUnlockPairedDevice"

    /// Saves or updates the login password in Keychain.
    @discardableResult
    static func savePassword(_ password: String) -> Bool {
        guard let data = password.data(using: .utf8) else { return false }

        deletePassword()

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountPassword,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        return status == errSecSuccess
    }

    /// Retrieves the saved login password from Keychain.
    static func getPassword() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountPassword,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var dataTypeRef: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &dataTypeRef)

        guard status == errSecSuccess, let data = dataTypeRef as? Data, let password = String(data: data, encoding: .utf8) else {
            return nil
        }
        return password
    }

    /// Deletes any saved password from Keychain.
    @discardableResult
    static func deletePassword() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountPassword
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    /// Returns whether a password is present in Keychain.
    static func hasPassword() -> Bool {
        getPassword() != nil
    }

    /// Securely saves the paired device records (including public key DER) to Keychain.
    ///
    /// Stored as one array under the original account name rather than an item per device: the
    /// whole set is a few hundred bytes, and reusing the account means an existing single-device
    /// record migrates by decode fallback in `getPairedDevices` instead of needing a migration step.
    @discardableResult
    static func savePairedDevices(_ devices: [PairedDevice]) -> Bool {
        guard let data = try? JSONEncoder().encode(devices) else { return false }

        deletePairedDevices()

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountPairedDevice,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        return status == errSecSuccess
    }

    /// Retrieves the paired device records securely from Keychain.
    ///
    /// Falls back to decoding a single record so a Mac paired before multi-device support keeps
    /// its phone: the old item is a bare `PairedDevice`, not an array.
    static func getPairedDevices() -> [PairedDevice] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountPairedDevice,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var dataTypeRef: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &dataTypeRef)

        guard status == errSecSuccess, let data = dataTypeRef as? Data else {
            return []
        }
        if let devices = try? JSONDecoder().decode([PairedDevice].self, from: data) {
            return devices
        }
        if let legacy = try? JSONDecoder().decode(PairedDevice.self, from: data) {
            EventLogger.shared.info(
                category: "Pairing",
                "Migrated single-device Keychain record to the multi-device format"
            )
            return [legacy]
        }
        // Data present but neither shape decoded: report it rather than silently reading as
        // unpaired, which would look identical to a user who had never paired.
        EventLogger.shared.error(category: "Pairing", "Keychain holds a paired-device record that could not be decoded")
        return []
    }

    /// Deletes all paired device records from Keychain.
    @discardableResult
    static func deletePairedDevices() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountPairedDevice
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}
