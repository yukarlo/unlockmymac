import CoreBluetooth
import Foundation

/// Fixed protocol identifiers and tunables shared with the Android BLE peripheral.
///
/// Per the security boundary in the plan, these 128-bit UUIDs are used *only* for
/// service discovery. They are never treated as an authentication credential — a
/// device that merely advertises this service UUID is not trusted until it completes
/// the signed GATT challenge-response.
enum BLEProtocol {
    /// Primary GATT service advertised by the paired Android phone.
    static let serviceUUID = CBUUID(string: "f9a2b8e3-54cd-4e92-a123-765432198765")

    /// Write-only characteristic the Mac uses to send a fresh challenge payload.
    static let challengeCharacteristicUUID = CBUUID(string: "f9a2b8e3-54cd-4e92-a123-765432198766")

    /// Read-only characteristic the Mac reads to retrieve the signed response.
    static let responseCharacteristicUUID = CBUUID(string: "f9a2b8e3-54cd-4e92-a123-765432198767")

    /// Write + Read characteristic used for zero-touch BLE pairing.
    static let pairingCharacteristicUUID = CBUUID(string: "f9a2b8e3-54cd-4e92-a123-765432198768")

    /// A paired device serves a signed offer here vouching for another device's public key.
    /// Read only when the user asks to add a device; empty the rest of the time.
    static let enrolmentCharacteristicUUID = CBUUID(string: "f9a2b8e3-54cd-4e92-a123-765432198769")

    /// Number of RSSI samples kept for rolling-average smoothing (plan section 8).
    static let rssiSampleWindow = 5

    /// A peripheral must average at/above this RSSI to be treated as a "near" candidate.
    static let nearRSSIThresholdDBm = -85

    /// A peripheral at/below this RSSI is treated only as a supporting absence signal.
    static let farRSSIThresholdDBm = -95

    /// Maximum time allowed for discovery + connect + full authentication handshake.
    ///
    /// Budget: two 4 s connect attempts (see `GATTChallengeClient.connectWatchdogSeconds`)
    /// plus a 0.3 s retry gap, ~0.9 s of service discovery and ~0.2 s for the challenge
    /// round trip — about 9.4 s worst case. A measured successful cycle is 1.8-2.9 s.
    static let authTimeoutSeconds: TimeInterval = 12

    /// The Mac gives up on an in-flight challenge after this long (must be <= Android's expiry).
    static let challengeExpirySeconds: TimeInterval = 10
}
