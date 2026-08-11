import AppKit
import Combine
import CoreBluetooth
import Foundation
import os

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

    /// Whether the last failure was a link problem rather than a rejected proof.
    private var lastFailureWasTransport = false

    /// Backoff after a rejected signature. Deliberately long — this is the path an attacker
    /// would exercise, and it must not be retried in a tight loop.
    private let authBackoffSeconds: TimeInterval = 10

    /// Backoff after a connection stall, timeout, or disconnect. These are benign and common
    /// (Android rotates its address constantly), so retrying quickly is the right behaviour.
    private let transportBackoffSeconds: TimeInterval = 3

    private var currentBackoffSeconds: TimeInterval {
        lastFailureWasTransport ? transportBackoffSeconds : authBackoffSeconds
    }
    private var heartbeatTimer: Timer?
    private var absenceTimer: Timer?
    private var wakeObserverToken: NSObjectProtocol?
    private let log = Logger(subsystem: "com.karloyu.macbleunlock", category: "PresenceStateMachine")

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
        observeSystemWake()
    }

    deinit {
        // Block-based observers are keyed by their token, not by `self`.
        wakeObserverToken.map(NSWorkspace.shared.notificationCenter.removeObserver)
    }

    /// Drops the pre-sleep view of the world on wake.
    ///
    /// Timers do not fire while the system is asleep and every discovered peripheral is a dead
    /// handle by the time we wake, so resuming the old state would mean re-acquiring against
    /// stale data — and possibly sitting in a backoff inherited from before the sleep.
    private func observeSystemWake() {
        wakeObserverToken = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            self.lastAuthFailureDate = nil
            self.lastFailureWasTransport = false
            self.authenticatedPeripheralId = nil
            if self.currentState != .absent {
                self.transitionTo(.absent)
            }
        }
    }

    /// Primary evaluation hook triggered on discovery / RSSI updates from `BLECentralManager`.
    private func observeDiscoveredPeripherals() {
        bleCentral.$discoveredPeripherals
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.evaluatePresence()
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

    private func evaluatePresence() {
        guard !isPaused, let paired = pairingManager.pairedDevice else {
            if currentState != .absent {
                transitionTo(.absent)
            }
            return
        }

        // Same freshness-aware selection the heartbeat and lock paths use. Picking an entry
        // without checking `lastSeenAt` can latch a rotated-away address, and connecting to a
        // dead address stalls for the full watchdog budget instead of failing fast.
        guard let entry = currentTarget(), let averageRSSI = entry.averageRSSI else {
            handleMissingPeripheral()
            return
        }

        resetAbsenceTimer()

        switch currentState {
        case .absent:
            // Check auth failure backoff cooldown to prevent tight retry loops
            if let lastFail = lastAuthFailureDate, Date().timeIntervalSince(lastFail) < currentBackoffSeconds {
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
                    // A bad signature is the security-relevant path: always the long backoff.
                    self.lastFailureWasTransport = false
                    EventLogger.shared.error(category: "Auth", "Authentication failed (invalid signature proof)")
                    self.transitionTo(.absent)
                }
            case .failure(let error):
                self.lastAuthFailureDate = Date()
                self.lastFailureWasTransport = error.isTransportLevel
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
            guard let self,
                  self.currentState == .unlockCooldown,
                  self.systemActionController.isScreenLocked,
                  let paired = self.pairingManager.pairedDevice else { return }
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
        // Also to the unified log, so state changes can be correlated with GATT timings in a
        // `log stream` capture rather than only being visible in the in-app Diagnostics window.
        log.info("State \(oldState.rawValue, privacy: .public) → \(newState.rawValue, privacy: .public)")

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
