import AppKit
import Combine
import CoreBluetooth
import Foundation
import os

/// Presence state machine states per Section 6.3 of the implementation plan.
enum PresenceState: String, CustomStringConvertible {
    case absent = "Device Away"
    case candidateNear = "Device Nearby"
    case connecting = "Connecting to Device…"
    case authenticating = "Authenticating…"
    case authenticatedNear = "Device Authenticated"
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

    /// Which paired device `authenticatedPeripheralId` actually is, by `deviceId`.
    ///
    /// The peripheral identifier alone cannot be attributed to a paired record — addresses rotate and
    /// are never identity — so anything wanting to say "this row is the device we are talking to"
    /// needs this as well. Without it the status menu credited the authenticated signal to *every*
    /// paired device, because the only test available matched the peripheral rather than the row.
    @Published private(set) var authenticatedDeviceId: String?
    private var lastAuthFailureDate: Date?

    /// Whether the last failure was a link problem rather than a rejected proof.
    private var lastFailureWasTransport = false

    /// Backoff after a rejected signature. Deliberately long — this is the path an attacker
    /// would exercise, and it must not be retried in a tight loop.
    private let authBackoffSeconds: TimeInterval = 10

    /// Backoff after a connection stall, timeout, or disconnect. These are benign and common
    /// (Android rotates its address constantly), so retrying quickly is the right behaviour.
    private let transportBackoffSeconds: TimeInterval = 3

    /// Set when the user denied the last request, so we wait rather than re-prompting.
    private var lastFailureWasDenial = false

    /// Backoff after an explicit "no". Long on purpose: the user said no, and each retry raises
    /// a fresh notification on their phone.
    ///
    /// A successful unlock is the only thing that clears it, so the next lock session starts
    /// fresh rather than inheriting the refusal. Deliberately *not* cleared by a display wake or
    /// a system wake: those are reachable by anyone standing at the Mac, and clearing there would
    /// turn "no" into "no until you shut the lid and open it again".
    private let deniedBackoffSeconds: TimeInterval = 120

    private var currentBackoffSeconds: TimeInterval {
        if lastFailureWasDenial { return deniedBackoffSeconds }
        return lastFailureWasTransport ? transportBackoffSeconds : authBackoffSeconds
    }
    private var heartbeatTimer: Timer?
    private var absenceTimer: Timer?
    private var wakeObserverToken: NSObjectProtocol?
    private var sleepObserverToken: NSObjectProtocol?
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
        observeDisplaySleepState()
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
        let center = NSWorkspace.shared.notificationCenter
        wakeObserverToken.map(center.removeObserver)
        sleepObserverToken.map(center.removeObserver)
    }

    /// Drops the pre-sleep view of the world on wake.
    ///
    /// Timers do not fire while the system is asleep and every discovered peripheral is a dead
    /// handle by the time we wake, so resuming the old state would mean re-acquiring against
    /// stale data — and possibly sitting in a backoff inherited from before the sleep.
    private func observeSystemWake() {
        // Sleeping mid-handshake is normal — an approval prompt in particular waits on a human.
        // Abandon the session explicitly so the state machine and the GATT client cannot end up
        // disagreeing about whether one is running.
        sleepObserverToken = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.willSleepNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self, self.gattClient.isBusy else { return }
            EventLogger.shared.info(category: "State", "Sleeping — abandoning in-flight handshake")
            self.gattClient.cancel()
        }

        wakeObserverToken = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            // Belt and braces: if a session somehow survived the sleep, drop it before
            // resetting state, or the two will disagree and every later handshake is refused.
            if self.gattClient.isBusy { self.gattClient.cancel() }
            // Only relevant while locked — that is the only time the radio is up.
            guard self.systemActionController.isScreenLocked else { return }
            // Same rule as the display-wake path: a stall from before the sleep is stale and
            // should not delay the user, but a refusal is a decision and keeps its full backoff.
            // Clearing it here made the two minutes bypassable by shutting the lid and opening
            // it again, which is the one thing the backoff exists to stop. Nothing needs
            // clearing for correctness — `lastAuthFailureDate` is wall-clock, so time spent
            // asleep already counts toward the backoff and a long sleep expires it naturally.
            if !self.lastFailureWasDenial {
                self.lastAuthFailureDate = nil
                self.lastFailureWasTransport = false
            }
            self.authenticatedPeripheralId = nil
            self.authenticatedDeviceId = nil
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

    /// True only when there is a lock screen in front of a human.
    ///
    /// Scanning continues while the display sleeps, so the phone is already discovered and its
    /// RSSI already averaged by the time this flips — the handshake starts from a warm cache
    /// rather than a cold scan, which is what makes the unlock feel immediate on a keypress.
    private var shouldChallengeNow: Bool {
        systemActionController.isScreenLocked && !systemActionController.isDisplayAsleep
    }

    /// Ties the handshake — not the radio — to the display being awake.
    ///
    /// The Mac used to wake its own display and unlock on approach, which meant walking past
    /// the desk to fetch something raised an approval prompt on the phone. Now the Mac waits to
    /// be woken deliberately, the same bargain Apple Watch unlock makes: one keypress buys
    /// silence the rest of the time.
    private func observeDisplaySleepState() {
        systemActionController.$isDisplayAsleep
            .receive(on: DispatchQueue.main)
            .removeDuplicates()
            .sink { [weak self] asleep in
                guard let self else { return }
                if asleep {
                    // An approval prompt waiting on a human is pointless once the screen the
                    // human would unlock into has gone dark; leaving it in flight would strand
                    // a notification on the phone for a login that can no longer happen.
                    if self.gattClient.isBusy { self.gattClient.cancel() }
                    self.stopHeartbeatTimer()
                    return
                }

                guard self.systemActionController.isScreenLocked else { return }
                let label = self.connectTarget().map { self.targetDeviceName(for: $0.peripheral) } ?? "paired device"
                EventLogger.shared.info(category: "State", "Lock screen shown — asking \(label) now")

                // A transport stall or a timeout from while the screen was dark should not
                // delay the unlock the user is now actively waiting for. A denial is different:
                // it is a decision, and it keeps its full backoff so "no" stays meaningful even
                // if the display is slept and woken to try to shake another prompt loose.
                if !self.lastFailureWasDenial {
                    self.lastAuthFailureDate = nil
                    self.lastFailureWasTransport = false
                }

                self.autoUnlockController.resetAttemptsForNewDisplayWake()
                if self.currentState != .absent { self.transitionTo(.absent) }
                self.evaluatePresence()
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
        guard !isPaused, pairingManager.isPaired else { return }
        EventLogger.shared.info(category: "State", "Screen locked — scanning for paired devices")
        bleCentral.start()

        // A device discovered before the lock is already stale; re-acquire from scratch.
        transitionTo(.absent)
    }

    /// Screen unlocked: shut the radio down and forget everything.
    private func stopScanningWhileUnlocked() {
        EventLogger.shared.info(category: "State", "Screen unlocked — stopping BLE until next lock")
        // Stopping the scan does not touch an in-flight connection, so without this the old
        // handshake keeps retrying and logging failures after we have supposedly shut down.
        if gattClient.isBusy { gattClient.cancel() }
        bleCentral.stop()
        stopHeartbeatTimer()
        resetAbsenceTimer()
        authenticatedPeripheralId = nil
        authenticatedDeviceId = nil
        lastAuthFailureDate = nil
        lastFailureWasTransport = false
        // A refusal applies to the lock session it was given in, not the next one.
        lastFailureWasDenial = false
        lastHeartbeatAuthDate = nil
        transitionTo(.absent)
    }

    private func evaluatePresence() {
        guard !isPaused, pairingManager.isPaired else {
            if currentState != .absent {
                transitionTo(.absent)
            }
            return
        }

        // Same freshness-aware selection the heartbeat and lock paths use. Picking an entry
        // without checking `lastSeenAt` can latch a rotated-away address, and connecting to a
        // dead address stalls for the full watchdog budget instead of failing fast.
        guard let entry = presenceTarget(), let averageRSSI = entry.averageRSSI else {
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

            // A handshake already running is not a reason to start another; asking anyway
            // returns `sessionAlreadyInProgress`, which used to be logged as an auth failure
            // and reset the state — thrashing until the in-flight session timed out.
            if gattClient.isBusy { return }

            // Track presence regardless, but only talk to the phone when a lock screen is up.
            // Everything above this line keeps running while the display sleeps, so the phone
            // stays discovered and the handshake can start the instant the screen comes back.
            guard shouldChallengeNow else { return }

            if averageRSSI >= nearRSSIThreshold {
                // `entry` answered "is the phone here?"; connecting needs the live handle,
                // which is the most recently heard one, not necessarily the strongest.
                guard let target = connectTarget() else { return }
                let targetName = targetDeviceName(for: target.peripheral)
                EventLogger.shared.info(category: "State", "Discovered candidate \(targetName) nearby (\(String(format: "%.1f", averageRSSI)) dBm)")
                transitionTo(.candidateNear)
                startHandshake(peripheral: target.peripheral)
            }

        case .candidateNear, .connecting, .authenticating:
            // Handshake in progress, waiting for GATTChallengeClient callback
            break

        case .authenticatedNear:
            transitionTo(.unlockCooldown)

        case .unlockCooldown:
            // Nothing to do while the phone remains visible. Presence loss is handled above by
            // `presenceTarget()` returning nil, not by an RSSI threshold — there is no longer a
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
    /// "Is the phone here?" — the strongest recent signal.
    ///
    /// Signal strength is the right proxy for proximity, so this drives presence and absence.
    private func presenceTarget() -> DiscoveredPeripheral? {
        candidates().max { ($0.averageRSSI ?? -200) < ($1.averageRSSI ?? -200) }
    }

    /// "Which handle is alive *and* worth connecting to?"
    ///
    /// Android mints a new private address on every `startAdvertising`, so one device can occupy
    /// several entries at once. Recency predicts which of them still exists; RSSI does not — a
    /// rotated-away address keeps whatever strength it was last heard at, and picking purely by
    /// strength happily selects a dead handle, which then stalls for the whole watchdog budget.
    ///
    /// Recency alone is not enough either, now that a phone and a watch can both be in range.
    /// It picks whichever happened to advertise last, which is arbitrary, and signal strength is
    /// what actually decides whether the handshake succeeds: measured on this hardware, -57 dBm
    /// connects cleanly, -66 stalls once, -74 fails outright.
    ///
    /// So: among handles heard within the last couple of seconds — all certainly alive, since
    /// the observed median advertisement gap is 0.29 s — take the strongest. Fall back to plain
    /// recency when nothing is that fresh, which is the old behaviour and the safe one.
    private func connectTarget() -> DiscoveredPeripheral? {
        let livenessCutoff = Date().addingTimeInterval(-Self.livenessWindowSeconds)
        let alive = candidates().filter { $0.lastSeenAt > livenessCutoff }

        // Choose among handles that are beyond doubt, and only fall back to the merely-alive when
        // there are none — which is what the paragraph above always described, but not what the
        // code did: the two tiers had collapsed into the single 8s window.
        //
        // Signal strength cannot arbitrate this on its own. An address keeps the average it earned
        // while it was live, so a handle the peer rotated away from seconds ago still reads strong
        // and beats its own fresher replacement. Measured 17:39:26 on the watch: the old handle at
        // 7.1s old and -59.6 dBm won over a replacement heard 16ms earlier at -76 dBm, and the Mac
        // spent 8.9s failing to reach an address that no longer existed before connecting to the
        // new one in 1.0s.
        // Never dial a handle we hung up on and have not heard from since — not even when it is
        // the only thing on offer. Waiting costs an advertising interval; measured 18:17:46, this
        // cost 12.5s: the display slept mid-approval, the peer rotated on the reconnect, and the
        // Mac spent two attempts on the address it had just been disconnected from before hearing
        // the replacement. Unlike the tier below, this one does decline to connect — a handle that
        // has been silent since we hung up on it is not a connection we would have made, it is a
        // connection we would have failed.
        let reachable = alive.filter(\.heardSinceDisconnect)

        let certain = reachable.filter(isCertainlyLive)
        let preferred = certain.isEmpty ? reachable : certain

        if let strongest = preferred.max(by: { ($0.averageRSSI ?? -200) < ($1.averageRSSI ?? -200) }) {
            return strongest
        }
        // No fallback to "least stale". Android mints a new address on every advertising
        // restart, so an entry last heard 15 s ago is a dead address, and dialling it costs the
        // full 9 s watchdog budget to learn nothing. Measured: handles 1.7 s old connect; ones
        // 14.6 s and 18.3 s old never reached the watch at all. Waiting for the next
        // advertisement is cheaper than talking to a corpse.
        return nil
    }

    /// How recently a handle must have been heard for it to be worth dialling.
    ///
    /// Above the worst observed gap between advertisements (5.73 s) so ordinary bursty reception
    /// is not mistaken for a dead address, and well below the 14 s+ ages that were measured
    /// never connecting.
    private static let livenessWindowSeconds: TimeInterval = 8

    /// Entries recent enough and strong enough to be this phone, honouring an existing pin.
    private func candidates() -> [DiscoveredPeripheral] {
        let peripherals = bleCentral.discoveredPeripherals
        let cutoff = Date().addingTimeInterval(-Self.presenceFreshnessSeconds)

        if let id = authenticatedPeripheralId {
            // `heardSinceDisconnect` first: the pin is set by a successful handshake, and the
            // disconnect that ends that handshake is exactly what rotates the peer's address.
            if let entry = peripherals[id],
               entry.lastSeenAt > cutoff,
               entry.heardSinceDisconnect,
               !isSupersededPin(entry, among: peripherals) {
                return [entry]
            }
            // Address rotated: drop the pin so we re-acquire under the new identifier.
            authenticatedPeripheralId = nil
        }

        return peripherals.values
            .filter { $0.lastSeenAt > cutoff && ($0.averageRSSI ?? -200) >= nearRSSIThreshold }
    }

    /// Whether the pinned handle has gone quiet while some other handle is demonstrably live.
    ///
    /// The pin collapses the candidate set to one entry, which is right while that entry is the
    /// device — but after a disconnect Android rotates its address, and the old handle can sit in
    /// the table for seconds still carrying the strong averaged RSSI it earned while it was alive.
    /// Being the only candidate, it wins by default and there is nothing to lose to.
    ///
    /// Measured 17:39:26 on the watch: the pinned handle was 7.1s old, its replacement had been
    /// heard 16ms earlier, and the pin sent the Mac to the dead one for 8.9s.
    private func isSupersededPin(
        _ pinned: DiscoveredPeripheral,
        among peripherals: [UUID: DiscoveredPeripheral]
    ) -> Bool {
        // A pin still advertising is the device; nothing supersedes it.
        guard !isCertainlyLive(pinned) else { return false }

        return peripherals.values.contains { other in
            other.id != pinned.id
                && isCertainlyLive(other)
                && (other.averageRSSI ?? -200) >= nearRSSIThreshold
        }
    }

    /// Heard recently enough that the handle is beyond doubt still being advertised.
    ///
    /// The observed median gap between advertisements is 0.29s, so a handle silent for this long
    /// has missed several in a row.
    private func isCertainlyLive(_ entry: DiscoveredPeripheral) -> Bool {
        entry.lastSeenAt > Date().addingTimeInterval(-Self.certainlyLiveWindowSeconds)
    }

    /// How recently a handle must have advertised to count as beyond doubt.
    ///
    /// Deliberately far tighter than `livenessWindowSeconds`, which exists to decide whether a
    /// handle is worth dialling *at all* when it is the only option. This one only ever chooses
    /// between handles, so it can afford to insist on a genuinely current one.
    private static let certainlyLiveWindowSeconds: TimeInterval = 2

    private func targetDeviceName(for peripheral: CBPeripheral) -> String {
        if let name = bleCentral.discoveredPeripherals[peripheral.identifier]?.name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return name.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let name = peripheral.name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return name.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let paired = pairingManager.pairedDevices.first(where: { $0.deviceId == peripheral.identifier.uuidString }) ?? pairingManager.pairedDevices.first {
            return paired.name
        }
        return "paired device"
    }

    private func startHandshake(peripheral: CBPeripheral) {
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
            macInstallationId: pairingManager.macInstallationId
        ) { [weak self] result in
            guard let self else { return }

            switch result {
            case .success(let verified):
                if verified {
                    self.authenticatedPeripheralId = peripheral.identifier
                    self.authenticatedDeviceId = self.gattClient.authenticatedDeviceId
                    // Who signed is reported by the client, which learned it from the key
                    // that verified. Nothing here needs to have guessed correctly beforehand.
                    EventLogger.shared.success(category: "Auth", "Authenticated presence confirmed")
                    self.onAuthenticationSuccess()
                } else {
                    self.lastAuthFailureDate = Date()
                    // A bad signature is the security-relevant path: always the long backoff.
                    self.lastFailureWasTransport = false
                    EventLogger.shared.error(category: "Auth", "Authentication failed (invalid signature proof)")
                    self.transitionTo(.absent)
                }
            case .failure(.sessionAlreadyInProgress):
                // Not a failure: another handshake owns the radio and will report its own
                // result. Resetting the state here is what caused the thrash loop — it made
                // the state machine believe it was idle and immediately try again.
                self.log.notice("Handshake already in progress; leaving the running one alone")

            case .failure(let error):
                // The watchdog already retried twice, so this handle is dead — drop it or the
                // next attempt selects the same one and stalls again.
                if case .connectionFailed = error {
                    self.bleCentral.forget(peripheralId: peripheral.identifier)
                }
                self.lastAuthFailureDate = Date()
                self.lastFailureWasTransport = error.isTransportLevel
                self.lastFailureWasDenial = error.isDenial
                EventLogger.shared.error(category: "Auth", "Authentication failed: \(error.description)")
                self.transitionTo(.absent)
            }
        }
    }

    private func onAuthenticationSuccess() {
        transitionTo(.authenticatedNear)

        // Assert valid user session before executing auto-unlock
        if systemActionController.isUserSessionActive {
            // No `wakeDisplay()` here any more. The handshake only runs with the display
            // already awake, so waking it was at best redundant; at worst `caffeinate -u`
            // asserted user activity, reset the system idle timer and kept the Mac from ever
            // reaching sleep while the phone sat nearby on the desk.
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

        // Presence lost. This no longer locks the Mac — proximity auto-lock was removed, and
        // macOS's own idle lock secures the session. All this does is drop back to `.absent`
        // after a grace window so a re-approach re-acquires cleanly. The grace exists because
        // BLE reception is bursty; thrashing here would burn auto-unlock attempts.
        if absenceTimer == nil {
            absenceTimer = Timer.scheduledTimer(withTimeInterval: absenceTimeoutSeconds, repeats: false) { [weak self] _ in
                guard let self else { return }
                EventLogger.shared.info(category: "State", "Phone no longer detected; will re-acquire on return")
                self.authenticatedPeripheralId = nil
                self.authenticatedDeviceId = nil
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
                  self.shouldChallengeNow,
                  self.pairingManager.isPaired else { return }

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
            guard let entry = self.connectTarget() else {
                // Nothing fresh enough to dial. Previously the beat was consumed before this
                // check and nothing was logged, so a retry could silently never happen — which
                // is how a failed keystroke sequence stayed at "attempt 1 of 3" for 36 seconds.
                self.log.notice("Heartbeat found no live handle; will look again on the next beat")
                return
            }
            self.lastHeartbeatAuthDate = Date()
            self.gattClient.authenticate(
                peripheral: entry.peripheral,
                macInstallationId: self.pairingManager.macInstallationId
            ) { [weak self] result in
                guard let self else { return }
                switch result {
                case .success(let verified):
                    // Re-checked rather than assumed: the display can sleep during the
                    // round trip, and typing a password into a Mac that just went dark
                    // would leak keystrokes into whatever has focus when it wakes.
                    if verified,
                       self.shouldChallengeNow,
                       self.autoUnlockController.hasAttemptsRemaining {
                        EventLogger.shared.info(category: "AutoUnlock", "Heartbeat verified phone presence while screen locked. Unlocking...")
                        self.autoUnlockController.attemptAutoUnlock()
                    }
                case .failure(let err):
                    EventLogger.shared.warning(category: "Heartbeat", "Heartbeat failed: \(err.description)")
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
        log.notice("State \(oldState.rawValue, privacy: .public) → \(newState.rawValue, privacy: .public)")

        if newState == .absent {
            stopHeartbeatTimer()
            resetAbsenceTimer()
            authenticatedPeripheralId = nil
            authenticatedDeviceId = nil
        }

        // Duplicates stay on in every state. Absence is inferred from `lastSeenAt`, which only
        // advances on a `didDiscover` callback — with duplicates suppressed CoreBluetooth
        // reports each peripheral once per scan, so `lastSeenAt` would freeze, the 30s stale
        // sweep would drop a phone sitting right there, and the Mac would lock itself and
        // re-authenticate on a loop.
        bleCentral.updateScanMode(allowDuplicates: true)
    }
}
