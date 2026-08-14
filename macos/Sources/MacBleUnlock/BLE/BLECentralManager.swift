import AppKit
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

    /// When we last hung up on this handle, if we ever have.
    var disconnectedAt: Date?

    /// False while this handle has not advertised since we last disconnected from it.
    ///
    /// Android mints a new address whenever it restarts advertising, and it restarts on every
    /// disconnect — so a handle that has been silent since we hung up on it is very likely an
    /// address the peer has already abandoned. Dialling one costs the full watchdog budget to
    /// learn nothing; waiting for the next advertisement costs an advertising interval.
    var heardSinceDisconnect: Bool {
        guard let disconnectedAt else { return true }
        return lastSeenAt > disconnectedAt
    }

    var averageRSSI: Double? { rssiSmoother.average }
    var isNear: Bool { rssiSmoother.hasFullWindow && rssiSmoother.isNear }

    static func == (lhs: DiscoveredPeripheral, rhs: DiscoveredPeripheral) -> Bool {
        lhs.id == rhs.id
            && lhs.lastSeenAt == rhs.lastSeenAt
            && lhs.rssiSmoother == rhs.rssiSmoother
            && lhs.disconnectedAt == rhs.disconnectedAt
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
        case unknown = "Initializing…"
        case resetting = "Resetting…"
        case unsupported = "Unsupported"
        case unauthorized = "Unauthorized"
        case poweredOff = "Powered Off"
        case poweredOn = "Powered On"
    }

    /// Current Bluetooth adapter/authorization state, mirrored to the main thread.
    @Published private(set) var adapterState: AdapterState = .unknown

    /// Whether a scan request is currently active with CoreBluetooth.
    @Published private(set) var isScanning: Bool = false

    /// Peripherals seen advertising the unlock service, keyed by CoreBluetooth identifier.
    ///
    /// Owned by the **main** queue: every mutation below dispatches there, and every SwiftUI and
    /// `PresenceStateMachine` reader observes it there too. Code running on `queue` must read
    /// `peripheralsOnQueue()` instead — see the mirror below.
    @Published private(set) var discoveredPeripherals: [UUID: DiscoveredPeripheral] = [:] {
        didSet {
            // Mirrored rather than locked. `queue` callers (the connect watchdog, the device-name
            // lookup) used to read the main-queue dictionary directly, which is a data race — and the
            // comment justifying it claimed the mutations happened on `queue`, which they never did.
            //
            // A snapshot is enough because those callers want a recent value, not a synchronised one:
            // they use it for a log line and a display name. Neither is worth a lock on the path that
            // handles every advertisement.
            let snapshot = discoveredPeripherals
            queue.async { [weak self] in self?.queueLocalPeripherals = snapshot }
        }
    }

    /// `queue`-confined mirror of `discoveredPeripherals`. Never touch from any other queue.
    private var queueLocalPeripherals: [UUID: DiscoveredPeripheral] = [:]

    /// The most recent view of `discoveredPeripherals` visible to `queue`. Must be called on `queue`.
    ///
    /// Lags the main-queue original by one dispatch hop, which is why this is deliberately a function
    /// rather than a property that reads like the real thing.
    func peripheralsOnQueue() -> [UUID: DiscoveredPeripheral] { queueLocalPeripherals }

    /// How long a peripheral can go unseen before it's dropped from `discoveredPeripherals`.
    ///
    /// Long enough to ride out bursty reception (measured max gap between advertisement
    /// callbacks: 5.73 s). A short timeout swept entries during ordinary gaps, which read as the
    /// phone leaving. Rotated-away addresses are handled where it matters — the connect watchdog
    /// in `GATTChallengeClient` — rather than by deleting evidence that the phone is present.
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

    /// Options the current scan was started with, so `updateScanMode` can no-op when unchanged.
    private var activeAllowDuplicates: Bool?

    /// Diagnostics for how long a fresh scan takes to hear its first advertisement.
    private var scanStartedAt: Date?
    private var loggedFirstSighting = false

    /// Tokens for the sleep/wake observers, removed in `deinit`.
    private var powerNotificationTokens: [NSObjectProtocol] = []

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: queue)
        observeSystemSleepWake()
    }

    deinit {
        let center = NSWorkspace.shared.notificationCenter
        powerNotificationTokens.forEach(center.removeObserver)
    }

    /// Re-arms scanning across a system sleep.
    ///
    /// CoreBluetooth does not scan while the system is asleep, and nothing restarts the scan on
    /// wake. `updateScanMode` cannot do it either: it is idempotent (deliberately, so it cannot
    /// abort an in-flight connection) and is only called from a state transition — but a state
    /// transition requires a discovery, which requires a scan. Without this the app can stay
    /// deaf after every wake.
    private func observeSystemSleepWake() {
        let center = NSWorkspace.shared.notificationCenter

        let willSleep = center.addObserver(
            forName: NSWorkspace.willSleepNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            // Forget the scan options so the wake path cannot be short-circuited by the
            // idempotence check in `updateScanMode`.
            self.queue.async { self.activeAllowDuplicates = nil }
        }

        let didWake = center.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleSystemWake()
        }

        powerNotificationTokens = [willSleep, didWake]
    }

    private func handleSystemWake() {
        guard wantsScanning else { return }
        log.notice("System woke; restarting scan and dropping stale peripherals")
        EventLogger.shared.info(category: "BLE", "Woke from sleep — rescanning")

        // Every entry predates the sleep, and Android has rotated its private address at least
        // once by now, so all of them are dead handles.
        discoveredPeripherals.removeAll()
        reclaimSystemConnections()
        restartScanning()
        startStaleSweepTimer()
    }

    /// Tears down links that bluetoothd is holding but nobody is using.
    ///
    /// macOS can keep a connection to the phone after the peer has dropped it — observed as
    /// `system_profiler` reporting the phone as Connected while the phone shows no LE ACL at
    /// all. In that state `connect()` never completes and never fails: no `didConnect`, no
    /// disconnect, just the watchdog firing forever. The app was wedged for eleven hours this
    /// way, with zero challenges written.
    ///
    /// `retrieveConnectedPeripherals` is the only way to see links owned by the system rather
    /// than by this process. Cancelling them puts the stack back into a state where a fresh
    /// connect can succeed.
    func reclaimSystemConnections() {
        queue.async { [weak self] in
            guard let self, self.centralManager.state == .poweredOn else { return }
            let held = self.centralManager.retrieveConnectedPeripherals(
                withServices: [BLEProtocol.serviceUUID]
            )
            guard !held.isEmpty else { return }
            self.log.notice("Reclaiming \(held.count) system-held connection(s)")
            EventLogger.shared.info(
                category: "BLE",
                "Clearing \(held.count) stale Bluetooth connection(s) held by macOS"
            )
            for peripheral in held {
                self.centralManager.cancelPeripheralConnection(peripheral)
            }
        }
    }

    /// Clears a system-held link to one peripheral, leaving links to other devices alone.
    ///
    /// The targeted counterpart to `reclaimSystemConnections`. Use this when a specific handle has
    /// exhausted its retries: the blanket version cancels every system-held link matching the service
    /// UUID, which with a phone and a watch both paired meant giving up on one dropped the other.
    ///
    /// Goes through `retrieveConnectedPeripherals` rather than cancelling `peripheral` directly,
    /// because the point is to catch links owned by bluetoothd rather than by this process — a
    /// `cancelPeripheralConnection` on a handle this process never connected is a no-op.
    func reclaimSystemConnection(for peripheral: CBPeripheral) {
        queue.async { [weak self] in
            guard let self, self.centralManager.state == .poweredOn else { return }
            let held = self.centralManager.retrieveConnectedPeripherals(
                withServices: [BLEProtocol.serviceUUID]
            )
            guard let match = held.first(where: { $0.identifier == peripheral.identifier }) else { return }
            self.log.notice("Reclaiming the system-held link to \(match.identifier.uuidString, privacy: .public)")
            EventLogger.shared.info(
                category: "BLE",
                "Clearing a stale Bluetooth connection held by macOS"
            )
            self.centralManager.cancelPeripheralConnection(match)
        }
    }

    /// Drops a peripheral known to be dead — a connect that exhausted its watchdog retries.
    ///
    /// Without this, a rotated-away address stays selectable for the whole `staleTimeout` and
    /// gets picked again on the next attempt, stalling for the full watchdog budget each time.
    func forget(peripheralId: UUID) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.discoveredPeripherals[peripheralId] != nil else { return }
            self.discoveredPeripherals.removeValue(forKey: peripheralId)
            self.log.notice("Forgot unreachable peripheral \(peripheralId.uuidString, privacy: .public)")
        }
    }

    /// Unconditionally tears the scan down and starts it again.
    ///
    /// Distinct from `updateScanMode`, which must stay idempotent. Use only when the scan is
    /// known to be dead or untrustworthy — currently just after a system wake.
    func restartScanning() {
        queue.async { [weak self] in
            guard let self, self.wantsScanning, self.centralManager.state == .poweredOn else { return }
            if self.centralManager.isScanning {
                self.centralManager.stopScan()
            }
            self.activeAllowDuplicates = true
            self.centralManager.scanForPeripherals(
                withServices: [BLEProtocol.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
            self.log.notice("Scan restarted")
            DispatchQueue.main.async { [weak self] in
                self?.isScanning = true
            }
        }
    }

    /// Begin scanning for the unlock service UUID.
    ///
    /// Safe to call before the adapter is powered on; scanning starts automatically
    /// once `centralManagerDidUpdateState` reports `.poweredOn`.
    func start() {
        wantsScanning = true
        // The system may still be holding a link from a previous session; clear it before
        // scanning, or the first connect will hang against a dead one.
        reclaimSystemConnections()
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
            self.activeAllowDuplicates = nil
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

    /// Adjusts scanning mode, but only when the mode actually changes.
    ///
    /// This must be idempotent. `stopScan()` followed by `scanForPeripherals()` aborts an
    /// in-flight connection, and the presence state machine calls this on every transition —
    /// including `.connecting` and `.authenticating`, which land in the middle of connection
    /// establishment. Restarting the scan there means the connection never completes and the
    /// handshake dies on its 8s timeout, every time.
    func updateScanMode(allowDuplicates: Bool) {
        queue.async { [weak self] in
            guard let self, self.wantsScanning, self.centralManager.state == .poweredOn else { return }
            if self.centralManager.isScanning, self.activeAllowDuplicates == allowDuplicates {
                return
            }
            if self.centralManager.isScanning {
                self.centralManager.stopScan()
            }
            self.activeAllowDuplicates = allowDuplicates
            self.centralManager.scanForPeripherals(
                withServices: [BLEProtocol.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates]
            )
            self.log.notice("Updated scan mode (allowDuplicates: \(allowDuplicates))")
        }
    }

    private func startScanningIfReady() {
        queue.async { [weak self] in
            guard let self, self.wantsScanning, self.centralManager.state == .poweredOn else { return }
            guard !self.centralManager.isScanning else { return }

            self.activeAllowDuplicates = true
            self.centralManager.scanForPeripherals(
                withServices: [BLEProtocol.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
            self.log.notice("Started scanning for service \(BLEProtocol.serviceUUID.uuidString, privacy: .public)")
            self.scanStartedAt = Date()
            self.loggedFirstSighting = false

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
        log.notice("Central manager state changed to \(mapped.rawValue, privacy: .public)")

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.adapterState = mapped
            if mapped != .poweredOn {
                self.isScanning = false
                self.discoveredPeripherals.removeAll()
            }
        }

        if mapped == .poweredOn {
            // Toggling Bluetooth is the manual cure for a wedged link; make sure the app also
            // clears anything the stack carried across the power cycle.
            reclaimSystemConnections()
            startScanningIfReady()
        } else {
            // The stack drops the scan when the adapter leaves poweredOn; forget the mode so
            // the next start actually issues a fresh scanForPeripherals.
            queue.async { [weak self] in self?.activeAllowDuplicates = nil }

            // Every connection died with the radio. Say so explicitly — CoreBluetooth does not
            // reliably deliver a disconnect here, and an in-flight session that keeps its
            // peripheral blocks all later attempts with `sessionAlreadyInProgress`.
            connectionDelegate?.bleCentralDidInvalidateConnections(self)
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

        // How long the radio took to hear anything at all, logged once per scan session.
        //
        // Measured across three locks, the gap between "Started scanning" and the state machine
        // acting on a candidate was 1.3s, 8.2s and 11.9s, with the peripheral advertising
        // continuously throughout and its RSSI (-67 dBm) far above the -85 threshold. That leaves
        // two possibilities which no existing log can tell apart: the advertisements were not
        // reaching us, or they were arriving and being filtered out downstream. This line is the
        // difference between them — if it prints promptly, the delay is ours.
        if !loggedFirstSighting {
            loggedFirstSighting = true
            let waited = scanStartedAt.map { now.timeIntervalSince($0) } ?? -1
            log.notice("""
                First advertisement after \(String(format: "%.2f", waited), privacy: .public)s \
                from \(peripheral.identifier.uuidString, privacy: .public) \
                (\(sampleRSSI, privacy: .public) dBm)
                """)
        }

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
        log.notice("Connected to \(peripheral.identifier.uuidString, privacy: .public)")
        connectionDelegate?.bleCentral(self, didConnect: peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        log.notice("Failed to connect to \(peripheral.identifier.uuidString, privacy: .public): \(String(describing: error), privacy: .public)")
        connectionDelegate?.bleCentral(self, didFailToConnect: peripheral, error: error)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        log.notice("Disconnected from \(peripheral.identifier.uuidString, privacy: .public)")
        // Stamped before the delegate runs: the delegate may immediately re-evaluate presence, and
        // this handle must already be marked as one we have not heard from since hanging up.
        let hungUpAt = Date()
        DispatchQueue.main.async { [weak self] in
            self?.discoveredPeripherals[peripheral.identifier]?.disconnectedAt = hungUpAt
        }
        connectionDelegate?.bleCentral(self, didDisconnect: peripheral, error: error)
    }
}
