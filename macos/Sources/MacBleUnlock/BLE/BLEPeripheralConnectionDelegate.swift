import CoreBluetooth

/// Receives per-peripheral connection lifecycle events from `BLECentralManager`.
///
/// `CBCentralManagerDelegate`'s connect/fail/disconnect callbacks are scoped to the
/// central manager, not to a specific in-flight operation, so `BLECentralManager`
/// forwards them to whichever object (e.g. `GATTChallengeClient`) currently owns the
/// one active connection attempt.
protocol BLEPeripheralConnectionDelegate: AnyObject {
    func bleCentral(_ manager: BLECentralManager, didConnect peripheral: CBPeripheral)
    func bleCentral(_ manager: BLECentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?)
    func bleCentral(_ manager: BLECentralManager, didDisconnect peripheral: CBPeripheral, error: Error?)
}
