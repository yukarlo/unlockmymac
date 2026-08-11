import AppKit
import ApplicationServices
import Foundation

/// Controls system actions (wake display, lock screen) and monitors lock screen state.
final class SystemActionController: ObservableObject {

    @Published private(set) var isScreenLocked: Bool = false

    private var notificationTokens: [NSObjectProtocol] = []

    private var reconcileTimer: Timer?

    init() {
        updateScreenLockState()
        observeScreenLockNotifications()
        startLockStateReconciliation()
    }

    deinit {
        notificationTokens.forEach { DistributedNotificationCenter.default().removeObserver($0) }
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
