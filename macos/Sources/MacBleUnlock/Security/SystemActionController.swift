import AppKit
import ApplicationServices
import Foundation

/// Controls system actions (wake display, lock screen) and monitors lock screen state.
final class SystemActionController: ObservableObject {

    @Published private(set) var isScreenLocked: Bool = false

    private var notificationTokens: [NSObjectProtocol] = []

    init() {
        updateScreenLockState()
        observeScreenLockNotifications()
    }

    deinit {
        notificationTokens.forEach { DistributedNotificationCenter.default().removeObserver($0) }
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
                self?.isScreenLocked = locked
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
