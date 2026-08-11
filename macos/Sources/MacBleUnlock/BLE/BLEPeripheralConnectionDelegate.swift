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

    /// Every connection is void — the adapter left `poweredOn`, or the system is going to sleep.
    ///
    /// CoreBluetooth does not reliably deliver a disconnect for connections torn down this way,
    /// so without an explicit signal an in-flight session keeps its `activePeripheral` set and
    /// silently blocks every later attempt with `sessionAlreadyInProgress`.
    func bleCentralDidInvalidateConnections(_ manager: BLECentralManager)
}
