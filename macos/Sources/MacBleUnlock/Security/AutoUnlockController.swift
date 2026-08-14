import AppKit
import ApplicationServices
import Carbon
import Combine
import Foundation

/// Translates characters to macOS CGVirtualKey codes and modifier flags.
struct KeyCodeMapper {

    struct KeyStroke {
        let keyCode: CGKeyCode
        let shift: Bool

        /// Whether Option has to be held for this key to produce the character.
        ///
        /// Needed for anything that is not on the unshifted or shifted face of a key, which on
        /// European layouts includes characters as ordinary as `@`, `\`, `~` and `€`. Without it those
        /// fell through to `postUnicodeChar`, and `loginwindow` discards synthetic Unicode under
        /// Secure Event Input — so a password containing one could never be typed.
        let option: Bool

        init(keyCode: CGKeyCode, shift: Bool, option: Bool = false) {
            self.keyCode = keyCode
            self.shift = shift
            self.option = option
        }
    }

    /// Maps a Character to the key and modifiers that produce it on the *user's* keyboard layout.
    ///
    /// Layout translation first, hardcoded ANSI table only as a fallback. The order used to be the
    /// other way round, which silently broke every non-US layout: the table is US-QWERTY, so on
    /// QWERTZ `y` and `z` swap, on AZERTY `a`/`q` and `w`/`z` swap, and Dvorak shares almost nothing
    /// with it. Every one of those characters was posted as the wrong hardware key, so the password
    /// was wrong and the lock screen simply refused it with nothing logged — the keystrokes were
    /// delivered exactly as asked, just not the ones intended.
    ///
    /// The table is still worth keeping. `ucKeyTranslate` returns nil when no key on the active layout
    /// produces the character, and falling back to US keycodes is a better last resort than giving up.
    static func keyStroke(for char: Character) -> KeyStroke? {
        if let stroke = ucKeyTranslate(char: char) {
            return stroke
        }
        return ansiKeyStroke(char: char)
    }

    private static func ansiKeyStroke(char: Character) -> KeyStroke? {
        switch char {
        case "a": return KeyStroke(keyCode: 0, shift: false)
        case "A": return KeyStroke(keyCode: 0, shift: true)
        case "b": return KeyStroke(keyCode: 11, shift: false)
        case "B": return KeyStroke(keyCode: 11, shift: true)
        case "c": return KeyStroke(keyCode: 8, shift: false)
        case "C": return KeyStroke(keyCode: 8, shift: true)
        case "d": return KeyStroke(keyCode: 2, shift: false)
        case "D": return KeyStroke(keyCode: 2, shift: true)
        case "e": return KeyStroke(keyCode: 14, shift: false)
        case "E": return KeyStroke(keyCode: 14, shift: true)
        case "f": return KeyStroke(keyCode: 3, shift: false)
        case "F": return KeyStroke(keyCode: 3, shift: true)
        case "g": return KeyStroke(keyCode: 5, shift: false)
        case "G": return KeyStroke(keyCode: 5, shift: true)
        case "h": return KeyStroke(keyCode: 4, shift: false)
        case "H": return KeyStroke(keyCode: 4, shift: true)
        case "i": return KeyStroke(keyCode: 34, shift: false)
        case "I": return KeyStroke(keyCode: 34, shift: true)
        case "j": return KeyStroke(keyCode: 38, shift: false)
        case "J": return KeyStroke(keyCode: 38, shift: true)
        case "k": return KeyStroke(keyCode: 40, shift: false)
        case "K": return KeyStroke(keyCode: 40, shift: true)
        case "l": return KeyStroke(keyCode: 37, shift: false)
        case "L": return KeyStroke(keyCode: 37, shift: true)
        case "m": return KeyStroke(keyCode: 46, shift: false)
        case "M": return KeyStroke(keyCode: 46, shift: true)
        case "n": return KeyStroke(keyCode: 45, shift: false)
        case "N": return KeyStroke(keyCode: 45, shift: true)
        case "o": return KeyStroke(keyCode: 31, shift: false)
        case "O": return KeyStroke(keyCode: 31, shift: true)
        case "p": return KeyStroke(keyCode: 35, shift: false)
        case "P": return KeyStroke(keyCode: 35, shift: true)
        case "q": return KeyStroke(keyCode: 12, shift: false)
        case "Q": return KeyStroke(keyCode: 12, shift: true)
        case "r": return KeyStroke(keyCode: 15, shift: false)
        case "R": return KeyStroke(keyCode: 15, shift: true)
        case "s": return KeyStroke(keyCode: 1, shift: false)
        case "S": return KeyStroke(keyCode: 1, shift: true)
        case "t": return KeyStroke(keyCode: 17, shift: false)
        case "T": return KeyStroke(keyCode: 17, shift: true)
        case "u": return KeyStroke(keyCode: 32, shift: false)
        case "U": return KeyStroke(keyCode: 32, shift: true)
        case "v": return KeyStroke(keyCode: 9, shift: false)
        case "V": return KeyStroke(keyCode: 9, shift: true)
        case "w": return KeyStroke(keyCode: 13, shift: false)
        case "W": return KeyStroke(keyCode: 13, shift: true)
        case "x": return KeyStroke(keyCode: 7, shift: false)
        case "X": return KeyStroke(keyCode: 7, shift: true)
        case "y": return KeyStroke(keyCode: 16, shift: false)
        case "Y": return KeyStroke(keyCode: 16, shift: true)
        case "z": return KeyStroke(keyCode: 6, shift: false)
        case "Z": return KeyStroke(keyCode: 6, shift: true)

        case "1": return KeyStroke(keyCode: 18, shift: false)
        case "!": return KeyStroke(keyCode: 18, shift: true)
        case "2": return KeyStroke(keyCode: 19, shift: false)
        case "@": return KeyStroke(keyCode: 19, shift: true)
        case "3": return KeyStroke(keyCode: 20, shift: false)
        case "#": return KeyStroke(keyCode: 20, shift: true)
        case "4": return KeyStroke(keyCode: 21, shift: false)
        case "$": return KeyStroke(keyCode: 21, shift: true)
        case "5": return KeyStroke(keyCode: 23, shift: false)
        case "%": return KeyStroke(keyCode: 23, shift: true)
        case "6": return KeyStroke(keyCode: 22, shift: false)
        case "^": return KeyStroke(keyCode: 22, shift: true)
        case "7": return KeyStroke(keyCode: 26, shift: false)
        case "&": return KeyStroke(keyCode: 26, shift: true)
        case "8": return KeyStroke(keyCode: 28, shift: false)
        case "*": return KeyStroke(keyCode: 28, shift: true)
        case "9": return KeyStroke(keyCode: 25, shift: false)
        case "(": return KeyStroke(keyCode: 25, shift: true)
        case "0": return KeyStroke(keyCode: 29, shift: false)
        case ")": return KeyStroke(keyCode: 29, shift: true)

        case "-": return KeyStroke(keyCode: 27, shift: false)
        case "_": return KeyStroke(keyCode: 27, shift: true)
        case "=": return KeyStroke(keyCode: 24, shift: false)
        case "+": return KeyStroke(keyCode: 24, shift: true)
        case "[": return KeyStroke(keyCode: 33, shift: false)
        case "{": return KeyStroke(keyCode: 33, shift: true)
        case "]": return KeyStroke(keyCode: 30, shift: false)
        case "}": return KeyStroke(keyCode: 30, shift: true)
        case "\\": return KeyStroke(keyCode: 42, shift: false)
        case "|": return KeyStroke(keyCode: 42, shift: true)
        case ";": return KeyStroke(keyCode: 41, shift: false)
        case ":": return KeyStroke(keyCode: 41, shift: true)
        case "'": return KeyStroke(keyCode: 39, shift: false)
        case "\"": return KeyStroke(keyCode: 39, shift: true)
        case ",": return KeyStroke(keyCode: 43, shift: false)
        case "<": return KeyStroke(keyCode: 43, shift: true)
        case ".": return KeyStroke(keyCode: 47, shift: false)
        case ">": return KeyStroke(keyCode: 47, shift: true)
        case "/": return KeyStroke(keyCode: 44, shift: false)
        case "?": return KeyStroke(keyCode: 44, shift: true)
        case "`": return KeyStroke(keyCode: 50, shift: false)
        case "~": return KeyStroke(keyCode: 50, shift: true)
        case " ": return KeyStroke(keyCode: 49, shift: false)
        default: return nil
        }
    }

    private static func ucKeyTranslate(char: Character) -> KeyStroke? {
        guard let inputSource = TISCopyCurrentKeyboardInputSource()?.takeRetainedValue() else {
            return nil
        }
        guard let layoutDataRaw = TISGetInputSourceProperty(inputSource, kTISPropertyUnicodeKeyLayoutData) else {
            return nil
        }
        let layoutData = Unmanaged<CFData>.fromOpaque(layoutDataRaw).takeUnretainedValue()
        guard let keyLayoutPtr = CFDataGetBytePtr(layoutData) else {
            return nil
        }
        let keyLayout = keyLayoutPtr.withMemoryRebound(to: UCKeyboardLayout.self, capacity: 1) { $0 }

        var deadKeyState: UInt32 = 0
        let maxStringLength = 4
        var actualStringLength = 0
        var unicodeString = [UniChar](repeating: 0, count: maxStringLength)

        let targetScalar = char.unicodeScalars.first?.value ?? 0

        // UCKeyTranslate takes the modifier byte from the old Carbon event record — the flags shifted
        // right by 8. So shiftKey (0x0200) is 1 << 1 and optionKey (0x0800) is 1 << 3.
        let shiftBit: UInt32 = 1 << 1
        let optionBit: UInt32 = 1 << 3

        // Plainest combination first, so a character reachable without modifiers is never reported as
        // needing them. Option-Shift is last for the same reason.
        let combinations: [(shift: Bool, option: Bool)] = [
            (false, false),
            (true, false),
            (false, true),
            (true, true),
        ]

        for keyCode in UInt16(0)...UInt16(127) {
            for combination in combinations {
                var modifierState: UInt32 = 0
                if combination.shift { modifierState |= shiftBit }
                if combination.option { modifierState |= optionBit }
                deadKeyState = 0
                actualStringLength = 0

                let result = UCKeyTranslate(
                    keyLayout,
                    keyCode,
                    UInt16(kUCKeyActionDisplay),
                    modifierState,
                    UInt32(LMGetKbdType()),
                    OptionBits(kUCKeyTranslateNoDeadKeysBit),
                    &deadKeyState,
                    maxStringLength,
                    &actualStringLength,
                    &unicodeString
                )

                // Length 1 exactly, not just non-zero. An Option key that is dead on this layout can
                // report the combining sequence it would begin, and posting that key alone would type
                // something else entirely.
                if result == noErr && actualStringLength == 1 && UInt32(unicodeString[0]) == targetScalar {
                    return KeyStroke(
                        keyCode: CGKeyCode(keyCode),
                        shift: combination.shift,
                        option: combination.option
                    )
                }
            }
        }
        return nil
    }
}

/// Manages Accessibility permissions and auto-unlock keystroke injection.
final class AutoUnlockController: ObservableObject {

    private let enabledKey = "com.karloyu.macbleunlock.autoUnlockEnabled"

    @Published var isEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isEnabled, forKey: enabledKey)
            EventLogger.shared.info(category: "AutoUnlock", "Auto-unlock toggled \(isEnabled ? "ON" : "OFF")")
        }
    }

    @Published private(set) var isAccessibilityAuthorized: Bool = false

    weak var systemActionController: SystemActionController?

    private var cancellables = Set<AnyCancellable>()
    private var pollTimer: Timer?

    /// Set once an attempt is made; cleared when screen locks or unlocks.
    /// Keystroke sequences actually delivered during the current lock session.
    ///
    /// A single attempt proved too brittle: one mistimed sequence (login window not ready yet)
    /// burned the session's only try, leaving the Mac locked for as long as the user was away.
    /// A small bounded number of spaced retries recovers from that without becoming a password
    /// guessing loop — macOS does not lock the account at this count.
    private var attemptsThisLockSession = 0
    private var lastAttemptDate: Date?

    /// True while auto-unlock could still act. Lets the heartbeat slow down once it cannot.
    var hasAttemptsRemaining: Bool {
        isEnabled && attemptsThisLockSession < Self.maxAttemptsPerLockSession
    }

    /// Gives a freshly shown lock screen a fresh set of attempts.
    ///
    /// The cap guards against a mistimed keystroke sequence turning into a loop, but a lock
    /// session can span days. Counting across the whole of one meant three unlucky attempts on
    /// a Monday morning left the Mac silent for the rest of the week, with nothing in the log to
    /// say why. Resetting per display wake keeps the loop protection where it belongs — within a
    /// single approach — and costs nothing: every attempt still needs a freshly verified P-256
    /// signature from the paired phone, and the password is the same correct one each time.
    func resetAttemptsForNewDisplayWake() {
        guard attemptsThisLockSession > 0 else { return }
        EventLogger.shared.info(
            category: "AutoUnlock",
            "Lock screen shown again — auto-unlock attempts reset"
        )
        attemptsThisLockSession = 0
        lastAttemptDate = nil
    }

    /// Virtual keycodes (ANSI layout-independent).
    private static let keyA: CGKeyCode = 0x00
    private static let keyReturn: CGKeyCode = 0x24
    private static let keyDelete: CGKeyCode = 0x33
    private static let keyCommand: CGKeyCode = 0x37
    private static let keyShift: CGKeyCode = 0x38
    private static let keyOption: CGKeyCode = 0x3A
    private static let keySpace: CGKeyCode = 0x31
    private static let keyEscape: CGKeyCode = 0x35

    /// Backstop in case the unlock notification is missed.
    /// Minimum gap between keystroke sequences within one lock session.
    private static let minRetryInterval: TimeInterval = 15

    /// Hard ceiling on keystroke sequences per lock session.
    private static let maxAttemptsPerLockSession = 3

    /// Backspaces sent before typing, to empty a field Select All may not have selected.
    ///
    /// Generous on purpose: the cost is linear and small, and the failure it prevents costs the
    /// user a manual password entry.
    private static let clearingBackspaces = 40

    /// Gap between synthesised keystrokes (8ms for fast typing without dropping keys).
    private static let keystrokeIntervalMicros: UInt32 = 8_000

    /// How long to wait for a sleeping display to light up before abandoning this attempt.
    private static let displayWakeTimeout: TimeInterval = 4.0

    /// Settle time after the display reports awake, before the login window accepts input.
    /// Settle time after the display reports awake.
    ///
    /// `CGDisplayIsAsleep` clears while the login window is still coming up, so keystrokes sent
    /// too soon are discarded — observed as a typed-but-ineffective unlock at 700 ms.
    private static let displaySettleMicros: UInt32 = 1_500_000

    init() {
        self.isEnabled = UserDefaults.standard.bool(forKey: enabledKey)
        self.isAccessibilityAuthorized = AXIsProcessTrusted()

        observeAppActivation()
        startAccessibilityPolling()
        observeScreenLockState()
    }

    /// Tokens for the `DistributedNotificationCenter` observers, removed in `deinit`.
    private var notificationTokens: [NSObjectProtocol] = []

    deinit {
        pollTimer?.invalidate()
        let center = DistributedNotificationCenter.default()
        notificationTokens.forEach(center.removeObserver)
    }

    /// Checks and updates current Accessibility authorization state.
    func checkAccessibilityPermission() {
        let authorized = AXIsProcessTrusted()
        DispatchQueue.main.async { [weak self] in
            if self?.isAccessibilityAuthorized != authorized {
                self?.isAccessibilityAuthorized = authorized
                EventLogger.shared.info(category: "AutoUnlock", "Accessibility permission state: \(authorized ? "GRANTED" : "NOT GRANTED")")
            }
        }
    }

    /// Prompts the user to grant Accessibility permissions and opens System Settings.
    func promptAccessibilityPermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)

        // Open System Settings -> Privacy & Security -> Accessibility
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
        checkAccessibilityPermission()
    }

    /// Performs keypress automation to enter password on the macOS lock screen.
    func attemptAutoUnlock() {
        checkAccessibilityPermission()

        guard isEnabled else {
            EventLogger.shared.info(category: "AutoUnlock", "Auto-unlock skipped (feature disabled)")
            return
        }

        guard isAccessibilityAuthorized else {
            EventLogger.shared.warning(category: "AutoUnlock", "Auto-unlock failed: Accessibility permission not granted")
            return
        }

        // Must be currently on lock screen! Do not consume session flag if screen is unlocked.
        if let systemActionController, !systemActionController.isScreenLocked {
            EventLogger.shared.info(category: "AutoUnlock", "Auto-unlock skipped (screen is not currently locked)")
            return
        }

        guard let password = KeychainManager.getPassword(), !password.isEmpty else {
            EventLogger.shared.warning(category: "AutoUnlock", "Auto-unlock failed: No password stored in Keychain")
            return
        }

        // Bounded attempts per lock session.
        if attemptsThisLockSession >= Self.maxAttemptsPerLockSession {
            EventLogger.shared.info(
                category: "AutoUnlock",
                "Auto-unlock exhausted for this lock session (\(attemptsThisLockSession) attempts)"
            )
            return
        }
        if let last = lastAttemptDate, Date().timeIntervalSince(last) < Self.minRetryInterval {
            return
        }
        lastAttemptDate = Date()

        EventLogger.shared.info(category: "AutoUnlock", "Executing auto-unlock keystroke sequence")

        // Read on the main thread, where it is written. The keystroke sequence runs on a
        // background queue, and reaching back into the controller from there would be a race.
        let displayAwakeSince = systemActionController?.displayAwakeSince

        DispatchQueue.global(qos: .userInteractive).async { [weak self] in
            guard let self else { return }

            // Step 1: Send Escape to wake display & clear screen saver / clock without inserting text
            self.postVirtualKey(Self.keyEscape)
            usleep(80_000)

            // Step 1b: Wait for the display to actually be awake.
            //
            // A sleeping display takes 0.5-2s to light up and present a ready password field.
            // Typing 80ms after the wake keystroke sends the password into nothing — which is
            // exactly what happens when the Mac locks and the screen then times off.
            guard self.waitForDisplayAwake(awakeSince: displayAwakeSince) else {
                // Do NOT consume the attempt: nothing was typed, so a retry is not a repeated
                // password guess. Clearing lastAttemptDate lets the next heartbeat try again.
                EventLogger.shared.warning(
                    category: "AutoUnlock",
                    "Display did not wake in time; skipping unlock without using this session's attempt"
                )
                DispatchQueue.main.async { self.lastAttemptDate = nil }
                return
            }

            // Step 2: Empty the password field.
            //
            // Cmd+A then Delete is the tidy way, but Select All is not honoured by every secure
            // input field, and if it is ignored a single Delete removes one character rather
            // than the lot. Whatever the user pressed to wake the Mac lands in this field first
            // — a letter, or a fistful of them if they mashed the keyboard — and the password
            // is then appended to it and rejected.
            //
            // So follow it with enough backspaces to empty the field outright. It costs about a
            // third of a second and removes a whole class of "typed but rejected" failures.
            self.postVirtualKey(Self.keyA, flags: .maskCommand)
            usleep(30_000)
            self.postVirtualKey(Self.keyDelete)
            usleep(30_000)
            for _ in 0..<Self.clearingBackspaces {
                self.postVirtualKey(Self.keyDelete)
                usleep(Self.keystrokeIntervalMicros)
            }
            usleep(40_000)

            // Step 3: Type password characters using physical CGKeyCodes & explicit Shift modifier events
            self.postPassword(password)
            usleep(40_000)

            // Step 4: Submit password via Return key
            self.postVirtualKey(Self.keyReturn)
            usleep(40_000)

            DispatchQueue.main.async {
                self.attemptsThisLockSession += 1
                EventLogger.shared.success(
                    category: "AutoUnlock",
                    "Auto-unlock keystroke sequence posted (attempt \(self.attemptsThisLockSession) of \(Self.maxAttemptsPerLockSession))"
                )
            }
        }
    }

    /// Re-arms auto-unlock whenever screen locks or unlocks.
    private func observeScreenLockState() {
        let center = DistributedNotificationCenter.default()

        // Tokens kept so `deinit` can deregister. Block-based observers are keyed by their token rather
        // than by `self`, so discarding them leaves the blocks registered for the lifetime of the
        // process. Harmless today — this controller lives as long as the app does, so `deinit` never
        // runs — but the same omission in `PresenceStateMachine` would be a real leak, and it is not
        // worth leaving one instance of the pattern wrong.
        notificationTokens.append(
            center.addObserver(
                forName: NSNotification.Name("com.apple.screenIsLocked"),
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.attemptsThisLockSession = 0
                self?.lastAttemptDate = nil
                EventLogger.shared.info(category: "AutoUnlock", "Screen locked — auto-unlock re-armed")
            }
        )

        notificationTokens.append(
            center.addObserver(
                forName: NSNotification.Name("com.apple.screenIsUnlocked"),
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.attemptsThisLockSession = 0
                self?.lastAttemptDate = nil
            }
        )
    }

    private func observeAppActivation() {
        NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.checkAccessibilityPermission()
            }
            .store(in: &cancellables)
    }

    private func startAccessibilityPolling() {
        pollTimer?.invalidate()
        let timer = Timer(timeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.checkAccessibilityPermission()
        }
        RunLoop.main.add(timer, forMode: .common)
        pollTimer = timer
    }

    /// Blocks until the main display reports awake, or the deadline passes.
    ///
    /// Called off the main thread from the keystroke sequence. Returns false if the display
    /// never woke, in which case the caller must not type — the login window is not ready and
    /// the keystrokes would be discarded.
    private func waitForDisplayAwake(awakeSince: Date?) -> Bool {
        let deadline = Date().addingTimeInterval(Self.displayWakeTimeout)
        while Date() < deadline {
            if CGDisplayIsAsleep(CGMainDisplayID()) == 0 {
                settleAfterDisplayWake(awakeSince: awakeSince)
                return true
            }
            usleep(100_000)
        }
        return CGDisplayIsAsleep(CGMainDisplayID()) == 0
    }

    /// Waits out whatever is left of the login window's settle time.
    ///
    /// The full settle is only owed when the display woke moments ago. Since the handshake is
    /// now gated on a lock screen already being on display, the display has typically been awake
    /// for several seconds by the time we type — the connect, discovery and approval round trips
    /// all happen first — and the wait is pure latency the user feels after tapping Approve.
    private func settleAfterDisplayWake(awakeSince: Date?) {
        let settle = TimeInterval(Self.displaySettleMicros) / 1_000_000
        let elapsed = awakeSince.map { Date().timeIntervalSince($0) } ?? 0
        let remaining = settle - elapsed
        guard remaining > 0 else { return }
        usleep(UInt32(remaining * 1_000_000))
    }

    private func postPassword(_ string: String) {
        for char in string {
            if let stroke = KeyCodeMapper.keyStroke(for: char) {
                postKeyStroke(stroke)
                usleep(Self.keystrokeIntervalMicros)
            } else {
                postUnicodeChar(char)
                usleep(Self.keystrokeIntervalMicros)
            }
        }
    }

    /// Posts one character as a real key press, holding whichever modifiers the layout requires.
    ///
    /// Modifiers are posted as their own key events rather than only as flags on the key itself.
    /// `loginwindow` tracks modifier state from those events, so a flags-only press is read as an
    /// unmodified one — which is why this has always pressed Shift explicitly. Option needs the same
    /// treatment, and both at once for Option-Shift characters.
    private func postKeyStroke(_ stroke: KeyCodeMapper.KeyStroke) {
        let source = CGEventSource(stateID: .hidSystemState)
        guard let keyDown = CGEvent(keyboardEventSource: source, virtualKey: stroke.keyCode, keyDown: true),
              let keyUp = CGEvent(keyboardEventSource: source, virtualKey: stroke.keyCode, keyDown: false) else { return }

        var flags: CGEventFlags = []
        var modifierKeys: [CGKeyCode] = []
        if stroke.shift {
            flags.insert(.maskShift)
            modifierKeys.append(Self.keyShift)
        }
        if stroke.option {
            flags.insert(.maskAlternate)
            modifierKeys.append(Self.keyOption)
        }

        guard !modifierKeys.isEmpty else {
            keyDown.post(tap: .cghidEventTap)
            keyUp.post(tap: .cghidEventTap)
            return
        }

        // Each modifier goes down carrying every flag held so far, matching what a real keyboard
        // reports as they accumulate.
        var accumulated: CGEventFlags = []
        for key in modifierKeys {
            accumulated.insert(key == Self.keyShift ? .maskShift : .maskAlternate)
            let down = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: true)
            down?.flags = accumulated
            down?.post(tap: .cghidEventTap)
        }
        usleep(5_000)

        keyDown.flags = flags
        keyUp.flags = flags
        keyDown.post(tap: .cghidEventTap)
        keyUp.post(tap: .cghidEventTap)
        usleep(5_000)

        // Released in reverse, each event carrying what is still held after it lifts — otherwise a
        // stuck modifier flag can bleed into the next character.
        for key in modifierKeys.reversed() {
            accumulated.remove(key == Self.keyShift ? .maskShift : .maskAlternate)
            let up = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: false)
            up?.flags = accumulated
            up?.post(tap: .cghidEventTap)
        }
    }

    private func postVirtualKey(_ virtualKey: CGKeyCode, flags: CGEventFlags = []) {
        let source = CGEventSource(stateID: .hidSystemState)
        guard let keyDown = CGEvent(keyboardEventSource: source, virtualKey: virtualKey, keyDown: true),
              let keyUp = CGEvent(keyboardEventSource: source, virtualKey: virtualKey, keyDown: false) else { return }

        if flags.contains(.maskCommand) {
            let cmdDown = CGEvent(keyboardEventSource: source, virtualKey: Self.keyCommand, keyDown: true)
            cmdDown?.flags = .maskCommand
            cmdDown?.post(tap: .cghidEventTap)
            usleep(5_000)

            keyDown.flags = flags
            keyUp.flags = flags
            keyDown.post(tap: .cghidEventTap)
            keyUp.post(tap: .cghidEventTap)
            usleep(5_000)

            let cmdUp = CGEvent(keyboardEventSource: source, virtualKey: Self.keyCommand, keyDown: false)
            cmdUp?.post(tap: .cghidEventTap)
        } else if flags.contains(.maskShift) {
            let shiftDown = CGEvent(keyboardEventSource: source, virtualKey: Self.keyShift, keyDown: true)
            shiftDown?.flags = .maskShift
            shiftDown?.post(tap: .cghidEventTap)
            usleep(5_000)

            keyDown.flags = flags
            keyUp.flags = flags
            keyDown.post(tap: .cghidEventTap)
            keyUp.post(tap: .cghidEventTap)
            usleep(5_000)

            let shiftUp = CGEvent(keyboardEventSource: source, virtualKey: Self.keyShift, keyDown: false)
            shiftUp?.post(tap: .cghidEventTap)
        } else {
            keyDown.post(tap: .cghidEventTap)
            keyUp.post(tap: .cghidEventTap)
        }
    }

    private func postUnicodeChar(_ char: Character) {
        let source = CGEventSource(stateID: .hidSystemState)
        for unit in char.utf16 {
            var codeUnit = unit
            guard let keyDown = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true),
                  let keyUp = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) else { continue }

            keyDown.keyboardSetUnicodeString(stringLength: 1, unicodeString: &codeUnit)
            keyUp.keyboardSetUnicodeString(stringLength: 1, unicodeString: &codeUnit)

            keyDown.post(tap: .cghidEventTap)
            keyUp.post(tap: .cghidEventTap)
        }
    }
}
