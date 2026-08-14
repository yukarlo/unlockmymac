import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

/// Controls system actions (wake display, lock screen) and monitors lock screen state.
final class SystemActionController: ObservableObject {

    @Published private(set) var isScreenLocked: Bool = false

    /// True while the displays are asleep, so there is no lock screen for anyone to look at.
    ///
    /// Gates the unlock handshake. Walking past a sleeping Mac should be silent: without this
    /// the phone raises an approval prompt for a login nobody asked for, and keeps raising one
    /// roughly every minute for as long as the Mac stays locked and the phone stays in range.
    @Published private(set) var isDisplayAsleep: Bool = false

    /// When the display last woke, or nil while it is asleep.
    ///
    /// Lets the unlock sequence skip a settle it has already waited out. `CGDisplayIsAsleep`
    /// only answers "is it awake now", and "awake for the last four seconds" is a materially
    /// different state from "awake as of this instant" when deciding whether the login window
    /// is ready to accept keystrokes.
    private(set) var displayAwakeSince: Date?

    private var notificationTokens: [NSObjectProtocol] = []

    private var workspaceTokens: [NSObjectProtocol] = []

    private var reconcileTimer: Timer?

    init() {
        updateScreenLockState()
        // Seeded before the first poll: `setDisplayAsleep` only records a wake on a transition,
        // and launching with the display already on is not one. Without this a relaunch would
        // look like "never woke" and pay the full settle on its first unlock.
        displayAwakeSince = CGDisplayIsAsleep(CGMainDisplayID()) == 0 ? Date() : nil
        updateDisplaySleepState()
        observeScreenLockNotifications()
        observeDisplaySleepNotifications()
        startLockStateReconciliation()
    }

    deinit {
        notificationTokens.forEach { DistributedNotificationCenter.default().removeObserver($0) }
        workspaceTokens.forEach { NSWorkspace.shared.notificationCenter.removeObserver($0) }
        reconcileTimer?.invalidate()
    }

    /// Keeps `isScreenLocked` honest against the session dictionary.
    ///
    /// The lock/unlock distributed notifications are edges, and they do not always come in
    /// pairs: a screensaver or display sleep can raise `com.apple.screenIsLocked` without the
    /// session ever truly locking, so no `com.apple.screenIsUnlocked` follows and the flag
    /// sticks `true` for the rest of the process. Everything gated on "locked" — the heartbeat,
    /// the re-authentication observer, auto-unlock — then runs forever against an unlocked Mac.
    ///
    /// `CGSessionCopyCurrentDictionary()` is the authoritative state, so poll it and correct.
    private func startLockStateReconciliation() {
        let timer = Timer(timeInterval: 5, repeats: true) { [weak self] _ in
            self?.updateScreenLockState()
            self?.updateDisplaySleepState()
        }
        RunLoop.main.add(timer, forMode: .common)
        reconcileTimer = timer
    }

    /// Asserts that a logged-in user desktop session is active (not at pre-boot/loginwindow/FileVault).
    var isUserSessionActive: Bool {
        guard let dict = CGSessionCopyCurrentDictionary() as? [String: Any],
              let userIsOnConsole = dict["kCGSSessionOnConsoleKey"] as? Bool else {
            return false
        }
        return userIsOnConsole && getuid() != 0
    }

    /// Queries macOS session state to determine if the screen is currently locked.
    func updateScreenLockState() {
        if let dict = CGSessionCopyCurrentDictionary() as? [String: Any] {
            let locked = (dict["CGSSessionScreenIsLocked"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in
                guard let self, self.isScreenLocked != locked else { return }
                // Only assign on a real change: @Published emits on every assignment, and the
                // re-authentication observer treats each `true` emission as a fresh lock event.
                self.isScreenLocked = locked
                EventLogger.shared.info(
                    category: "System",
                    "Lock state corrected from session: \(locked ? "locked" : "unlocked")"
                )
            }
        }
    }

    /// Reads the authoritative display-sleep state.
    ///
    /// `CGMainDisplayID()` follows the display carrying the menu bar, so on a Mac in clamshell
    /// mode this reports the external panel rather than the shut lid — which is the state that
    /// matters, because that is the screen the login window appears on.
    func updateDisplaySleepState() {
        setDisplayAsleep(CGDisplayIsAsleep(CGMainDisplayID()) != 0, source: "session poll")
    }

    /// Observes display sleep and wake.
    ///
    /// `NSWorkspace` posts these only when *every* display sleeps or the first one wakes, which
    /// is the behaviour we want with an external monitor attached: a shut lid beside a lit
    /// external screen is not "away".
    ///
    /// Paired with the reconciliation poll for the same reason the lock state needed one —
    /// these are edges, and a missed edge would strand the flag in the wrong position.
    private func observeDisplaySleepNotifications() {
        let center = NSWorkspace.shared.notificationCenter

        let sleepToken = center.addObserver(
            forName: NSWorkspace.screensDidSleepNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.setDisplayAsleep(true, source: "notification")
        }

        let wakeToken = center.addObserver(
            forName: NSWorkspace.screensDidWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.setDisplayAsleep(false, source: "notification")
        }

        workspaceTokens = [sleepToken, wakeToken]
    }

    private func setDisplayAsleep(_ asleep: Bool, source: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isDisplayAsleep != asleep else { return }
            // Only assign on a real change: subscribers treat each emission as a fresh edge, and
            // a repeated "awake" would re-trigger the handshake on every poll.
            self.isDisplayAsleep = asleep
            self.displayAwakeSince = asleep ? nil : Date()
            EventLogger.shared.info(
                category: "System",
                "Display \(asleep ? "asleep" : "awake") (\(source))"
            )
        }
    }

    /// Wakes the display by invoking `/usr/bin/caffeinate -u -t 2`.
    func wakeDisplay() {
        EventLogger.shared.info(category: "System", "Waking display via caffeinate")
        DispatchQueue.global(qos: .userInitiated).async {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/caffeinate")
            process.arguments = ["-u", "-t", "2"]
            try? process.run()
        }
    }

    /// Locks the macOS session.
    func lockScreen() {
        EventLogger.shared.info(category: "System", "Executing macOS session lock")

        // Primary method: SACLockScreenImmediate from login.framework
        if let handle = dlopen("/System/Library/PrivateFrameworks/login.framework/Versions/A/login", RTLD_LAZY) {
            typealias SACLockScreenImmediateFunc = @convention(c) () -> Void
            if let sym = dlsym(handle, "SACLockScreenImmediate") {
                let lockFunc = unsafeBitCast(sym, to: SACLockScreenImmediateFunc.self)
                lockFunc()
                dlclose(handle)
                return
            }
            dlclose(handle)
        }

        // Fallback method 1: CGSession -suspend
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession")
        process.arguments = ["-suspend"]
        if (try? process.run()) != nil {
            return
        }

        // Fallback method 2: AppleScript Ctrl+Cmd+Q
        let script = NSAppleScript(source: "tell application \"System Events\" to key code 12 using {control down, command down}")
        var error: NSDictionary?
        script?.executeAndReturnError(&error)
    }

    private func observeScreenLockNotifications() {
        let center = DistributedNotificationCenter.default()

        let lockToken = center.addObserver(
            forName: NSNotification.Name("com.apple.screenIsLocked"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.isScreenLocked = true
            EventLogger.shared.info(category: "System", "Screen locked")
        }

        let unlockToken = center.addObserver(
            forName: NSNotification.Name("com.apple.screenIsUnlocked"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.isScreenLocked = false
            EventLogger.shared.info(category: "System", "Screen unlocked")
        }

        notificationTokens = [lockToken, unlockToken]
    }
}
