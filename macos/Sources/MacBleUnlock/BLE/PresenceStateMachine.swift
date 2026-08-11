import Combine
import CoreBluetooth
import Foundation

/// Presence state machine states per Section 6.3 of the implementation plan.
enum PresenceState: String, CustomStringConvertible {
    case absent = "Absent"
    case candidateNear = "Candidate Near"
    case connecting = "Connecting"
    case authenticating = "Authenticating"
    case authenticatedNear = "Authenticated (Near)"
    case unlockCooldown = "Unlock Cooldown"

    var description: String { rawValue }
}

/// Drives the BLE proximity state machine, GATT challenge execution, and system actions.
final class PresenceStateMachine: ObservableObject {

    @Published private(set) var currentState: PresenceState = .absent
    @Published var isPaused: Bool = false {
        didSet {
            EventLogger.shared.info(category: "State", "State machine \(isPaused ? "PAUSED" : "RESUMED")")
            if isPaused {
                transitionTo(.absent)
            }
        }
    }

    /// User-adjustable RSSI thresholds (defaults from Section 8).
    ///
    /// Persisted: these are per-desk calibration values, and silently resetting them to the
    /// defaults on every launch means the app stops seeing the phone until the user notices
    /// and recalibrates.
    @Published var nearRSSIThreshold: Double = Defaults.near {
        didSet { UserDefaults.standard.set(nearRSSIThreshold, forKey: Defaults.nearKey) }
    }
    @Published var farRSSIThreshold: Double = Defaults.far {
        didSet { UserDefaults.standard.set(farRSSIThreshold, forKey: Defaults.farKey) }
    }
    @Published var absenceTimeoutSeconds: TimeInterval = Defaults.absence {
        didSet { UserDefaults.standard.set(absenceTimeoutSeconds, forKey: Defaults.absenceKey) }
    }

    private enum Defaults {
        static let nearKey = "com.karloyu.macbleunlock.nearRSSIThreshold"
        static let farKey = "com.karloyu.macbleunlock.farRSSIThreshold"
        static let absenceKey = "com.karloyu.macbleunlock.absenceTimeoutSeconds"

        static var near: Double {
            UserDefaults.standard.object(forKey: nearKey) as? Double
                ?? Double(BLEProtocol.nearRSSIThresholdDBm)
        }
        static var far: Double {
            UserDefaults.standard.object(forKey: farKey) as? Double
                ?? Double(BLEProtocol.farRSSIThresholdDBm)
        }
        static var absence: TimeInterval {
            UserDefaults.standard.object(forKey: absenceKey) as? Double ?? 30
        }
    }

    private let bleCentral: BLECentralManager
    private let gattClient: GATTChallengeClient
    private let pairingManager: PairingManager
    private let systemActionController: SystemActionController
    private let autoUnlockController: AutoUnlockController

    private var cancellables = Set<AnyCancellable>()
    @Published private(set) var authenticatedPeripheralId: UUID?
    private var lastAuthFailureDate: Date?
    private let authBackoffSeconds: TimeInterval = 10
    private var heartbeatTimer: Timer?
    private var absenceTimer: Timer?

    init(
        bleCentral: BLECentralManager,
        gattClient: GATTChallengeClient,
        pairingManager: PairingManager,
        systemActionController: SystemActionController,
        autoUnlockController: AutoUnlockController
    ) {
        self.bleCentral = bleCentral
        self.gattClient = gattClient
        self.pairingManager = pairingManager
        self.systemActionController = systemActionController
        self.autoUnlockController = autoUnlockController

        observeDiscoveredPeripherals()
        observeScreenLockState()
    }

    /// Primary evaluation hook triggered on discovery / RSSI updates from `BLECentralManager`.
    private func observeDiscoveredPeripherals() {
        bleCentral.$discoveredPeripherals
            .receive(on: DispatchQueue.main)
            .sink { [weak self] peripherals in
                self?.evaluatePresence(peripherals: peripherals)
            }
            .store(in: &cancellables)
    }

    /// Re-evaluates presence & triggers authentication whenever macOS screen locks.
    private func observeScreenLockState() {
        systemActionController.$isScreenLocked
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isLocked in
                guard let self, isLocked else { return }
                // If screen becomes locked while phone is authenticated or in cooldown, trigger re-authentication for auto-unlock
                if self.currentState == .unlockCooldown || self.currentState == .authenticatedNear {
                    EventLogger.shared.info(category: "State", "Screen locked while phone is nearby. Re-authenticating for auto-unlock...")
                    self.transitionTo(.candidateNear)
                    if let target = self.currentTarget(), let paired = self.pairingManager.pairedDevice {
                        self.startHandshake(peripheral: target.peripheral, paired: paired)
                    } else {
                        EventLogger.shared.warning(
                            category: "State",
                            "Screen locked but no freshly advertising phone to re-authenticate"
                        )
                        self.transitionTo(.absent)
                    }
                }
            }
            .store(in: &cancellables)
    }

    private func evaluatePresence(peripherals: [UUID: DiscoveredPeripheral]) {
        guard !isPaused, let paired = pairingManager.pairedDevice else {
            if currentState != .absent {
                transitionTo(.absent)
            }
            return
        }

        // If locked onto an authenticated paired peripheral, target that specific UUID; otherwise fallback to candidate
        let pairedEntry: DiscoveredPeripheral?
        if let targetId = authenticatedPeripheralId, let existing = peripherals[targetId] {
            pairedEntry = existing
        } else {
            // Find candidate advertising peripheral with valid RSSI sample
            pairedEntry = peripherals.values.first { entry in
                guard let rssi = entry.averageRSSI else { return false }
                return rssi >= nearRSSIThreshold
            }
        }

        guard let entry = pairedEntry, let averageRSSI = entry.averageRSSI else {
            handleMissingPeripheral()
            return
        }

        resetAbsenceTimer()

        switch currentState {
        case .absent:
            // Check auth failure backoff cooldown to prevent tight retry loops
            if let lastFail = lastAuthFailureDate, Date().timeIntervalSince(lastFail) < authBackoffSeconds {
                return
            }

            if averageRSSI >= nearRSSIThreshold {
                EventLogger.shared.info(category: "State", "Discovered candidate phone nearby (\(String(format: "%.1f", averageRSSI)) dBm)")
                transitionTo(.candidateNear)
                startHandshake(peripheral: entry.peripheral, paired: paired)
            }

        case .candidateNear, .connecting, .authenticating:
            // Handshake in progress, waiting for GATTChallengeClient callback
            break

        case .authenticatedNear:
            if averageRSSI <= farRSSIThreshold {
                EventLogger.shared.warning(category: "State", "Phone RSSI dropped below far threshold (\(String(format: "%.1f", averageRSSI)) dBm)")
                handleMissingPeripheral()
            } else {
                transitionTo(.unlockCooldown)
            }

        case .unlockCooldown:
            if averageRSSI <= farRSSIThreshold {
                handleMissingPeripheral()
            }
        }
    }

    /// The peripheral we should talk to right now.
    ///
    /// Android mints a new resolvable private address on every `startAdvertising`, and it
    /// restarts advertising after each disconnect — so a peripheral identifier is only valid
    /// for as long as advertisements keep arriving under it. Connecting to a rotated-away
    /// address does not fail fast; CoreBluetooth simply never completes, so the handshake dies
    /// on its 8s timeout instead. Requiring a recent `lastSeenAt` avoids that entirely.
    ///
    /// Falling back to the strongest fresh candidate is safe: the address is never identity
    /// here, the signature is. It also replaces an arbitrary `Dictionary.first`.
    private func currentTarget(freshWithin: TimeInterval = 5) -> DiscoveredPeripheral? {
        let peripherals = bleCentral.discoveredPeripherals
        let cutoff = Date().addingTimeInterval(-freshWithin)

        if let id = authenticatedPeripheralId {
            if let entry = peripherals[id], entry.lastSeenAt > cutoff { return entry }
            // Address rotated: drop the pin so we re-acquire under the new identifier.
            authenticatedPeripheralId = nil
        }

        return peripherals.values
            .filter { $0.lastSeenAt > cutoff && ($0.averageRSSI ?? -200) >= nearRSSIThreshold }
            .max { ($0.averageRSSI ?? -200) < ($1.averageRSSI ?? -200) }
    }

    private func startHandshake(peripheral: CBPeripheral, paired: PairedDevice) {
        transitionTo(.connecting)

        // Enter authenticating state once GATT connection connects
        bleCentral.queue.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self, self.currentState == .connecting else { return }
            DispatchQueue.main.async {
                self.transitionTo(.authenticating)
            }
        }

        gattClient.authenticate(
            peripheral: peripheral,
            macInstallationId: pairingManager.macInstallationId,
            pairedDevice: paired
        ) { [weak self] result in
            guard let self else { return }

            switch result {
            case .success(let verified):
                if verified {
                    self.authenticatedPeripheralId = peripheral.identifier
                    EventLogger.shared.success(category: "Auth", "Authenticated presence confirmed for '\(paired.name)'")
                    self.onAuthenticationSuccess()
                } else {
                    self.lastAuthFailureDate = Date()
                    EventLogger.shared.error(category: "Auth", "Authentication failed (invalid signature proof)")
                    self.transitionTo(.absent)
                }
            case .failure(let error):
                self.lastAuthFailureDate = Date()
                EventLogger.shared.error(category: "Auth", "Authentication failed: \(error.description)")
                self.transitionTo(.absent)
            }
        }
    }

    private func onAuthenticationSuccess() {
        transitionTo(.authenticatedNear)

        // Assert valid user session before executing wake / auto-unlock
        if systemActionController.isUserSessionActive {
            systemActionController.wakeDisplay()
            if systemActionController.isScreenLocked {
                autoUnlockController.attemptAutoUnlock()
            }
        } else {
            EventLogger.shared.warning(category: "Security", "Skipped auto-unlock: system at pre-boot/loginwindow")
        }

        // Transition to unlockCooldown to avoid repeated unlock triggers during this cycle
        transitionTo(.unlockCooldown)
        startHeartbeatTimer()
    }

    private func handleMissingPeripheral() {
        guard currentState == .authenticatedNear || currentState == .unlockCooldown else {
            if currentState != .absent && currentState != .connecting && currentState != .authenticating {
                transitionTo(.absent)
            }
            return
        }

        // Start absence window timer (30s) to avoid transient BLE drop locks
        if absenceTimer == nil {
            EventLogger.shared.info(category: "State", "Phone signal lost/far, starting \(Int(absenceTimeoutSeconds))s absence grace window")
            absenceTimer = Timer.scheduledTimer(withTimeInterval: absenceTimeoutSeconds, repeats: false) { [weak self] _ in
                EventLogger.shared.warning(category: "State", "Sustained absence confirmed, locking macOS session")
                self?.authenticatedPeripheralId = nil
                self?.transitionTo(.absent)
                self?.systemActionController.lockScreen()
            }
        }
    }

    private func resetAbsenceTimer() {
        absenceTimer?.invalidate()
        absenceTimer = nil
    }

    private func startHeartbeatTimer() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            guard let self, self.currentState == .unlockCooldown, let paired = self.pairingManager.pairedDevice else { return }
            if let entry = self.currentTarget() {
                self.gattClient.authenticate(
                    peripheral: entry.peripheral,
                    macInstallationId: self.pairingManager.macInstallationId,
                    pairedDevice: paired
                ) { [weak self] result in
                    guard let self else { return }
                    switch result {
                    case .success(let verified):
                        if verified && self.systemActionController.isScreenLocked {
                            EventLogger.shared.info(category: "AutoUnlock", "Heartbeat verified phone presence while screen locked. Unlocking...")
                            self.systemActionController.wakeDisplay()
                            self.autoUnlockController.attemptAutoUnlock()
                        }
                    case .failure(let err):
                        EventLogger.shared.warning(category: "Heartbeat", "Heartbeat failed: \(err.description)")
                    }
                }
            }
        }
    }

    private func stopHeartbeatTimer() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
    }

    private func transitionTo(_ newState: PresenceState) {
        guard currentState != newState else { return }
        let oldState = currentState
        currentState = newState
        EventLogger.shared.info(category: "State", "Presence state changed: \(oldState) → \(newState)")

        if newState == .absent {
            stopHeartbeatTimer()
            resetAbsenceTimer()
            authenticatedPeripheralId = nil
        }

        // Duplicates stay on in every state. Absence is inferred from `lastSeenAt`, which only
        // advances on a `didDiscover` callback — with duplicates suppressed CoreBluetooth
        // reports each peripheral once per scan, so `lastSeenAt` would freeze, the 30s stale
        // sweep would drop a phone sitting right there, and the Mac would lock itself and
        // re-authenticate on a loop.
        bleCentral.updateScanMode(allowDuplicates: true)
    }
}
