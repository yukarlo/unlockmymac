import CoreBluetooth
import Foundation
import os

/// One BLE peripheral currently (or recently) seen advertising the unlock service UUID.
///
/// `id` is CoreBluetooth's session-local peripheral identifier. It is a convenient
/// handle for reconnecting during this app run, but per the plan's non-negotiable
/// rules it must never be treated as the phone's identity — that comes only from the
/// signed `deviceId` proven via the GATT challenge-response, added separately.
struct DiscoveredPeripheral: Equatable {
    let id: UUID
    var peripheral: CBPeripheral
    var name: String
    var rssiSmoother: RSSISmoother
    var lastSeenAt: Date

    var averageRSSI: Double? { rssiSmoother.average }
    var isNear: Bool { rssiSmoother.hasFullWindow && rssiSmoother.isNear }

    static func == (lhs: DiscoveredPeripheral, rhs: DiscoveredPeripheral) -> Bool {
        lhs.id == rhs.id && lhs.lastSeenAt == rhs.lastSeenAt && lhs.rssiSmoother == rhs.rssiSmoother
    }
}

/// Scans for BLE peripherals advertising `BLEProtocol.serviceUUID` and maintains a
/// smoothed RSSI reading per peripheral.
///
/// This is discovery only (plan Milestone 1): it never connects, authenticates, or
/// triggers any Mac action by itself. RSSI here is purely a proximity *hint* that a
/// later state machine will use to decide when to attempt the cryptographic GATT
/// handshake.
final class BLECentralManager: NSObject, ObservableObject {

    enum AdapterState: String {
        case unknown, resetting, unsupported, unauthorized, poweredOff, poweredOn
    }

    /// Current Bluetooth adapter/authorization state, mirrored to the main thread.
    @Published private(set) var adapterState: AdapterState = .unknown

    /// Whether a scan request is currently active with CoreBluetooth.
    @Published private(set) var isScanning: Bool = false

    /// Peripherals seen advertising the unlock service, keyed by CoreBluetooth identifier.
    @Published private(set) var discoveredPeripherals: [UUID: DiscoveredPeripheral] = [:]

    /// How long a peripheral can go unseen before it's dropped from `discoveredPeripherals`.
    var staleTimeout: TimeInterval = 30

    /// Receives connect/fail/disconnect callbacks for a single in-flight GATT session
    /// (e.g. `GATTChallengeClient`). Only one consumer is supported at a time, which
    /// mirrors the "one active authentication session" rule enforced on the Android side.
    weak var connectionDelegate: BLEPeripheralConnectionDelegate?

    private var centralManager: CBCentralManager!

    /// Serial queue backing the central manager and every peripheral it vends.
    /// CoreBluetooth guarantees delegate callbacks for peripherals obtained from this
    /// central manager also land on this queue, so GATT session code (see
    /// `GATTChallengeClient`) hops onto it explicitly rather than assuming the main
    /// thread. Internal (not private) so that collaborator classes can dispatch onto it.
    let queue = DispatchQueue(label: "com.karloyu.macbleunlock.ble.central")
    private let log = Logger(subsystem: "com.karloyu.macbleunlock", category: "BLECentralManager")
    private var staleSweepTimer: Timer?

    /// Tracks whether the caller wants scanning to be active; scanning resumes
    /// automatically once the adapter reaches `.poweredOn` if this is true.
    private var wantsScanning = false

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: queue)
    }

    /// Begin scanning for the unlock service UUID.
    ///
    /// Safe to call before the adapter is powered on; scanning starts automatically
    /// once `centralManagerDidUpdateState` reports `.poweredOn`.
    func start() {
        wantsScanning = true
        startScanningIfReady()
        startStaleSweepTimer()
    }

    /// Stop scanning and clear known peripherals.
    func stop() {
        wantsScanning = false
        queue.async { [weak self] in
            guard let self else { return }
            if self.centralManager.isScanning {
                self.centralManager.stopScan()
            }
            DispatchQueue.main.async { [weak self] in
                self?.isScanning = false
            }
        }
        stopStaleSweepTimer()
    }

    /// Initiates a connection to `peripheral`. The result arrives via `connectionDelegate`.
    func connect(_ peripheral: CBPeripheral) {
        queue.async { [weak self] in
            self?.centralManager.connect(peripheral, options: nil)
        }
    }

    /// Cancels an in-flight or established connection to `peripheral`.
    func cancelConnection(_ peripheral: CBPeripheral) {
        queue.async { [weak self] in
            guard let self else { return }
            if peripheral.state == .connected || peripheral.state == .connecting {
                self.centralManager.cancelPeripheralConnection(peripheral)
            }
        }
    }

    /// Adjusts scanning mode (e.g. enabling allowDuplicates for near-field discovery, or disabling for battery savings when unlocked).
    func updateScanMode(allowDuplicates: Bool) {
        queue.async { [weak self] in
            guard let self, self.wantsScanning, self.centralManager.state == .poweredOn else { return }
            if self.centralManager.isScanning {
                self.centralManager.stopScan()
            }
            self.centralManager.scanForPeripherals(
                withServices: [BLEProtocol.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates]
            )
            self.log.info("Updated scan mode (allowDuplicates: \(allowDuplicates))")
        }
    }

    private func startScanningIfReady() {
        queue.async { [weak self] in
            guard let self, self.wantsScanning, self.centralManager.state == .poweredOn else { return }
            guard !self.centralManager.isScanning else { return }

            self.centralManager.scanForPeripherals(
                withServices: [BLEProtocol.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
            self.log.info("Started scanning for service \(BLEProtocol.serviceUUID.uuidString, privacy: .public)")

            DispatchQueue.main.async { [weak self] in
                self?.isScanning = true
            }
        }
    }

    private func startStaleSweepTimer() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.staleSweepTimer?.invalidate()
            let timer = Timer(timeInterval: 5, repeats: true) { [weak self] _ in
                self?.sweepStalePeripherals()
            }
            RunLoop.main.add(timer, forMode: .common)
            self.staleSweepTimer = timer
        }
    }

    private func stopStaleSweepTimer() {
        DispatchQueue.main.async { [weak self] in
            self?.staleSweepTimer?.invalidate()
            self?.staleSweepTimer = nil
        }
    }

    private func sweepStalePeripherals() {
        let cutoff = Date().addingTimeInterval(-staleTimeout)
        discoveredPeripherals = discoveredPeripherals.filter { _, entry in entry.lastSeenAt > cutoff }
    }
}

extension BLECentralManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let mapped: AdapterState
        switch central.state {
        case .unknown: mapped = .unknown
        case .resetting: mapped = .resetting
        case .unsupported: mapped = .unsupported
        case .unauthorized: mapped = .unauthorized
        case .poweredOff: mapped = .poweredOff
        case .poweredOn: mapped = .poweredOn
        @unknown default: mapped = .unknown
        }
        log.info("Central manager state changed to \(mapped.rawValue, privacy: .public)")

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.adapterState = mapped
            if mapped != .poweredOn {
                self.isScanning = false
                self.discoveredPeripherals.removeAll()
            }
        }

        if mapped == .poweredOn {
            startScanningIfReady()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // CoreBluetooth reports +127 when RSSI is unavailable for a given callback.
        guard RSSI.intValue != 127 else { return }

        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name
            ?? "Unknown device"
        let sampleRSSI = RSSI.intValue
        let now = Date()

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            var entry = self.discoveredPeripherals[peripheral.identifier] ?? DiscoveredPeripheral(
                id: peripheral.identifier,
                peripheral: peripheral,
                name: name,
                rssiSmoother: RSSISmoother(),
                lastSeenAt: now
            )
            entry.peripheral = peripheral
            entry.name = name
            entry.rssiSmoother.addSample(sampleRSSI)
            entry.lastSeenAt = now
            self.discoveredPeripherals[peripheral.identifier] = entry
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        log.info("Connected to \(peripheral.identifier.uuidString, privacy: .public)")
        connectionDelegate?.bleCentral(self, didConnect: peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        log.info("Failed to connect to \(peripheral.identifier.uuidString, privacy: .public): \(String(describing: error), privacy: .public)")
        connectionDelegate?.bleCentral(self, didFailToConnect: peripheral, error: error)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        log.info("Disconnected from \(peripheral.identifier.uuidString, privacy: .public)")
        connectionDelegate?.bleCentral(self, didDisconnect: peripheral, error: error)
    }
}
