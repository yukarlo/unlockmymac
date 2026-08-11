import AppKit
import Combine
import CoreBluetooth
import Foundation
import os

/// Presence state machine states per Section 6.3 of the implementation plan.
enum PresenceState: String, CustomStringConvertible {
    case absent = "Phone Away"
    case candidateNear = "Phone Nearby"
    case connecting = "Connecting to Phone…"
    case authenticating = "Authenticating…"
    case authenticatedNear = "Phone Authenticated"
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
    private var lastHeartbeatAuthDate: Date?

    /// How stale an advertisement may be before the phone counts as gone.
    ///
    /// Measured against the real advertisement stream: 12 callbacks in 25 s, median gap 0.29 s,
    /// **max gap 5.73 s**. The previous 5 s window sat inside that distribution, so ordinary
    /// bursty reception was repeatedly mistaken for the phone leaving. 15 s is ~3x the observed
    /// worst case.
    private static let presenceFreshnessSeconds: TimeInterval = 15

    /// Heartbeat cadence while auto-unlock can still act on a verified presence.
    private static let heartbeatActiveSeconds: TimeInterval = 10

    /// Slower cadence once it cannot — presence is still confirmed, just less often.
    private static let heartbeatIdleSeconds: TimeInterval = 30
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

        // Launching while already locked (login item, or a restart during a lock session) still
        // needs the radio up. The 5s reconciliation poll in SystemActionController corrects the
        // flag shortly after if this initial read is wrong, and the observer follows.
        if systemActionController.isScreenLocked {
            beginScanningForUnlock()
        }
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
            // Only relevant while locked — that is the only time the radio is up.
            guard self.systemActionController.isScreenLocked else { return }
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
            .removeDuplicates()
            .sink { [weak self] isLocked in
                guard let self else { return }
                if isLocked {
                    self.beginScanningForUnlock()
                } else {
                    self.stopScanningWhileUnlocked()
                }
            }
            .store(in: &cancellables)
    }

    /// Screen locked: bring the radio up and try to unlock.
    ///
    /// Scanning exists only to serve auto-unlock, so it runs only while the Mac is locked. That
    /// keeps the radio idle during normal use and removes the false-absence problem entirely —
    /// bursty BLE reception can no longer be mistaken for the phone leaving, because nothing is
    /// watching for the phone leaving any more.
    private func beginScanningForUnlock() {
        guard !isPaused, pairingManager.pairedDevice != nil else { return }
        EventLogger.shared.info(category: "State", "Screen locked — scanning for the paired phone")
        bleCentral.start()

        // A phone discovered before the lock is already stale; re-acquire from scratch.
        transitionTo(.absent)
    }

    /// Screen unlocked: shut the radio down and forget everything.
    private func stopScanningWhileUnlocked() {
        EventLogger.shared.info(category: "State", "Screen unlocked — stopping BLE until next lock")
        bleCentral.stop()
        stopHeartbeatTimer()
        resetAbsenceTimer()
        authenticatedPeripheralId = nil
        lastAuthFailureDate = nil
        lastHeartbeatAuthDate = nil
        transitionTo(.absent)
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
            transitionTo(.unlockCooldown)

        case .unlockCooldown:
            // Nothing to do while the phone remains visible. Presence loss is handled above by
            // `currentTarget()` returning nil, not by an RSSI threshold — there is no longer a
            // lock action to trigger, so a "far" band buys nothing and only added a second way
            // to mistake bursty reception for departure.
            break
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
    private func currentTarget(freshWithin: TimeInterval = presenceFreshnessSeconds) -> DiscoveredPeripheral? {
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
            let locked = systemActionController.isScreenLocked
            // Wake on approach when the session is open, or when locked and we can still act.
            // Waking a locked Mac we cannot unlock only burns battery: `caffeinate -u` resets
            // the system idle timer, so it would also stop the Mac ever reaching sleep.
            if !locked || autoUnlockController.hasAttemptsRemaining {
                systemActionController.wakeDisplay()
            }
            if locked {
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

        // Presence lost. This no longer locks the Mac — proximity auto-lock was removed, and
        // macOS's own idle lock secures the session. All this does is drop back to `.absent`
        // after a grace window so a re-approach re-acquires cleanly. The grace exists because
        // BLE reception is bursty; thrashing here would burn auto-unlock attempts.
        if absenceTimer == nil {
            absenceTimer = Timer.scheduledTimer(withTimeInterval: absenceTimeoutSeconds, repeats: false) { [weak self] _ in
                guard let self else { return }
                EventLogger.shared.info(category: "State", "Phone no longer detected; will re-acquire on return")
                self.authenticatedPeripheralId = nil
                self.transitionTo(.absent)
            }
        }
    }

    private func resetAbsenceTimer() {
        absenceTimer?.invalidate()
        absenceTimer = nil
    }

    private func startHeartbeatTimer() {
        heartbeatTimer?.invalidate()
        // Fresh cycle: let the first beat fire without waiting out the previous cadence.
        lastHeartbeatAuthDate = nil
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            guard let self,
                  self.currentState == .unlockCooldown,
                  self.systemActionController.isScreenLocked,
                  let paired = self.pairingManager.pairedDevice else { return }

            // Back off once auto-unlock can no longer act. Each beat is a full
            // connect/authenticate/disconnect, and every disconnect makes the phone restart
            // advertising and rotate its address — 40 cycles in five minutes of being away,
            // for no benefit once the attempts are spent.
            let interval: TimeInterval = self.autoUnlockController.hasAttemptsRemaining
                ? Self.heartbeatActiveSeconds
                : Self.heartbeatIdleSeconds
            if let last = self.lastHeartbeatAuthDate, Date().timeIntervalSince(last) < interval {
                return
            }
            self.lastHeartbeatAuthDate = Date()
            if let entry = self.currentTarget() {
                self.gattClient.authenticate(
                    peripheral: entry.peripheral,
                    macInstallationId: self.pairingManager.macInstallationId,
                    pairedDevice: paired
                ) { [weak self] result in
                    guard let self else { return }
                    switch result {
                    case .success(let verified):
                        // Only wake the display if we can actually act on it. `caffeinate -u`
                        // asserts user activity, so waking on every beat resets the system idle
                        // timer and the Mac can never reach sleep while the phone is nearby —
                        // draining the battery and flapping the display on and off, even when
                        // auto-unlock is disabled or its attempts are spent.
                        if verified,
                           self.systemActionController.isScreenLocked,
                           self.autoUnlockController.hasAttemptsRemaining {
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
