import CryptoKit
import Foundation
import Security

/// Cryptographic services for generating random challenges and verifying Android signatures.
enum CryptoManager {

    /// Generates cryptographically secure random bytes for a challenge.
    static func generateRandomBytes(count: Int = 32) -> Data? {
        var bytes = Data(count: count)
        let result = bytes.withUnsafeMutableBytes { buffer -> Int32 in
            guard let baseAddress = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, count, baseAddress)
        }
        return result == errSecSuccess ? bytes : nil
    }

    /// Verifies an ECDSA P-256 (secp256r1) signature against message data using an X.509 DER public key.
    ///
    /// - Parameters:
    ///   - signatureData: DER-encoded ASN.1 ECDSA signature bytes (`SHA256withECDSA`).
    ///   - messageData: The exact UTF-8 byte sequence signed by the Android app.
    ///   - publicKeyDER: X.509 SubjectPublicKeyInfo DER-encoded P-256 public key.
    /// - Returns: `true` if the signature is valid for the message and public key; `false` otherwise.
    static func verifySignature(
        signatureData: Data,
        messageData: Data,
        publicKeyDER: Data
    ) -> Bool {
        guard !signatureData.isEmpty, !messageData.isEmpty, !publicKeyDER.isEmpty else {
            return false
        }

        // Try standard X.509 DER SubjectPublicKeyInfo loading via CryptoKit P256
        if let key = try? P256.Signing.PublicKey(derRepresentation: publicKeyDER) {
            // First attempt DER-encoded signature verification (standard Android Keystore output)
            if let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureData) {
                if key.isValidSignature(signature, for: messageData) {
                    return true
                }
            }
            // Fallback: try raw r+s concatenated (64 bytes) signature representation if DER parsing failed
            if let signature = try? P256.Signing.ECDSASignature(rawRepresentation: signatureData) {
                if key.isValidSignature(signature, for: messageData) {
                    return true
                }
            }
        }

        // Fallback: try raw uncompressed EC public key (65 bytes 0x04 || X || Y) if raw representation supplied
        if let key = try? P256.Signing.PublicKey(rawRepresentation: publicKeyDER) {
            if let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureData) {
                if key.isValidSignature(signature, for: messageData) {
                    return true
                }
            }
        }

        return false
    }
}
