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

    /// Number of RSSI samples kept for rolling-average smoothing (plan section 8).
    static let rssiSampleWindow = 5

    /// A peripheral must average at/above this RSSI to be treated as a "near" candidate.
    static let nearRSSIThresholdDBm = -75

    /// A peripheral at/below this RSSI is treated only as a supporting absence signal.
    static let farRSSIThresholdDBm = -90

    /// Maximum time allowed for discovery + connect + full authentication handshake.
    static let authTimeoutSeconds: TimeInterval = 8

    /// The Mac gives up on an in-flight challenge after this long (must be <= Android's expiry).
    static let challengeExpirySeconds: TimeInterval = 10
}
