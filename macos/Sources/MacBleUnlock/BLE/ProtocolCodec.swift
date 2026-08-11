import Foundation

/// Constructs and parses deterministic protocol messages shared with the Android app.
enum ProtocolCodec {
    /// Formats Base64URL string (RFC 4648 without padding).
    static func base64UrlEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Decodes Base64URL string (RFC 4648 without padding).
    static func base64UrlDecode(_ string: String) -> Data? {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 {
            base64.append("=")
        }
        return Data(base64Encoded: base64)
    }

    /// Challenge payload sent to the Android BLE peripheral over the challenge characteristic.
    struct ChallengeRequest {
        let macInstallationId: String
        let deviceId: String
        let issuedAtMs: Int64
        let challengeData: Data

        var challengeBase64Url: String {
            ProtocolCodec.base64UrlEncode(challengeData)
        }

        /// Deterministic UTF-8 payload string per Section 4 of the protocol specification.
        var payloadString: String {
            """
            mac-ble-unlock:v1
            macInstallationId=\(macInstallationId)
            deviceId=\(deviceId)
            issuedAt=\(issuedAtMs)
            challenge=\(challengeBase64Url)
            """
        }

        var payloadData: Data {
            Data(payloadString.utf8)
        }
    }
}
