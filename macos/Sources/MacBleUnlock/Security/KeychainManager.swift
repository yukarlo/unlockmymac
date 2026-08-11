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

    /// Securely saves paired device record (including public key DER) to Keychain.
    @discardableResult
    static func savePairedDevice(_ device: PairedDevice) -> Bool {
        guard let data = try? JSONEncoder().encode(device) else { return false }

        deletePairedDevice()

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

    /// Retrieves paired device record securely from Keychain.
    static func getPairedDevice() -> PairedDevice? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountPairedDevice,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var dataTypeRef: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &dataTypeRef)

        guard status == errSecSuccess, let data = dataTypeRef as? Data,
              let device = try? JSONDecoder().decode(PairedDevice.self, from: data) else {
            return nil
        }
        return device
    }

    /// Deletes paired device record from Keychain.
    @discardableResult
    static func deletePairedDevice() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountPairedDevice
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}
