import Carbon
import CoreGraphics
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

    /// The active layout's `UCKeyboardLayout` blob, captured on the main thread.
    ///
    /// Text Input Services asserts it is called on the main thread — `TISCopyCurrentKeyboardInputSource`
    /// traps with `EXC_BREAKPOINT` in `dispatch_assert_queue` anywhere else. The keystroke sequence
    /// runs on a background queue, so the layout has to be fetched separately from being used.
    ///
    /// This only became reachable when `keyStroke(for:)` started consulting the layout *first*: with
    /// the ANSI table tried first, every ASCII character matched before TIS was ever touched, so the
    /// crash sat behind a path that a normal password never took. It killed the app on the first
    /// character of the first auto-unlock.
    ///
    /// `UCKeyTranslate` itself only reads this blob and is safe on any thread, so caching it is all
    /// that is needed.
    private static let layoutLock = NSLock()
    private nonisolated(unsafe) static var cachedLayoutData: CFData?

    /// Captures the active keyboard layout. **Must be called on the main thread.**
    ///
    /// Call before dispatching a keystroke sequence, so the mapping reflects the layout in effect at
    /// that moment rather than whatever was current the last time anyone asked.
    static func refreshLayout() {
        assert(Thread.isMainThread, "Text Input Services must be called on the main thread")
        guard let source = TISCopyCurrentKeyboardInputSource()?.takeRetainedValue(),
              let raw = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData)
        else { return }
        let data = Unmanaged<CFData>.fromOpaque(raw).takeUnretainedValue()
        layoutLock.lock()
        cachedLayoutData = data
        layoutLock.unlock()
    }

    private static func currentLayoutData() -> CFData? {
        layoutLock.lock()
        let cached = cachedLayoutData
        layoutLock.unlock()
        if let cached { return cached }
        // Nothing captured yet. Safe to fetch only if we happen to be on the main thread; otherwise
        // the caller falls back to the ANSI table, which is wrong on some layouts but does not trap.
        guard Thread.isMainThread else { return nil }
        refreshLayout()
        layoutLock.lock()
        let fetched = cachedLayoutData
        layoutLock.unlock()
        return fetched
    }

    private static func ucKeyTranslate(char: Character) -> KeyStroke? {
        guard let layoutData = currentLayoutData() else { return nil }
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
