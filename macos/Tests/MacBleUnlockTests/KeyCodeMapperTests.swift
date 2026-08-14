import Carbon
import XCTest

@testable import MacBleUnlock

/// Guards the layout mapping that silently broke every non-US keyboard.
///
/// `keyStroke(for:)` consults the *active* keyboard layout, so these cannot assert fixed keycodes —
/// those are correct only on ANSI. Instead they assert the property that actually matters: whatever
/// key and modifiers come back must translate back into the character that was asked for. That holds
/// on every layout, which is exactly the bug that was missed.
final class KeyCodeMapperTests: XCTestCase {

    /// Runs the mapping backwards through the active layout, so a wrong keycode fails rather than
    /// silently typing a different letter.
    private func character(from stroke: KeyCodeMapper.KeyStroke) -> String? {
        guard let source = TISCopyCurrentKeyboardInputSource()?.takeRetainedValue(),
              let raw = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData)
        else { return nil }
        let data = Unmanaged<CFData>.fromOpaque(raw).takeUnretainedValue()
        guard let ptr = CFDataGetBytePtr(data) else { return nil }
        let layout = ptr.withMemoryRebound(to: UCKeyboardLayout.self, capacity: 1) { $0 }

        var modifiers: UInt32 = 0
        if stroke.shift { modifiers |= 1 << 1 }
        if stroke.option { modifiers |= 1 << 3 }

        var dead: UInt32 = 0
        var length = 0
        var chars = [UniChar](repeating: 0, count: 4)
        let status = UCKeyTranslate(
            layout,
            stroke.keyCode,
            UInt16(kUCKeyActionDisplay),
            modifiers,
            UInt32(LMGetKbdType()),
            OptionBits(kUCKeyTranslateNoDeadKeysBit),
            &dead,
            4,
            &length,
            &chars
        )
        guard status == noErr, length > 0 else { return nil }
        return String(utf16CodeUnits: chars, count: length)
    }

    private func assertRoundTrips(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        for character in text {
            guard let stroke = KeyCodeMapper.keyStroke(for: character) else {
                XCTFail("no keystroke for '\(character)'", file: file, line: line)
                continue
            }
            XCTAssertEqual(
                self.character(from: stroke),
                String(character),
                "'\(character)' maps to a key that types something else on this layout",
                file: file,
                line: line
            )
        }
    }

    func testLowercaseLettersRoundTrip() {
        assertRoundTrips("abcdefghijklmnopqrstuvwxyz")
    }

    /// y, z, q, a and w are the letters that move between QWERTY, QWERTZ and AZERTY, so they are the
    /// ones the hardcoded ANSI table got wrong.
    func testTheLettersThatMoveBetweenLayoutsRoundTrip() {
        assertRoundTrips("yzqaw")
    }

    func testUppercaseLettersRoundTripAndRequireShift() {
        assertRoundTrips("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        for character in "ABZ" {
            guard let stroke = KeyCodeMapper.keyStroke(for: character) else {
                XCTFail("no keystroke for '\(character)'")
                continue
            }
            XCTAssertTrue(stroke.shift, "'\(character)' should need Shift")
        }
    }

    func testDigitsRoundTrip() {
        assertRoundTrips("0123456789")
    }

    func testCommonPasswordPunctuationRoundTrips() {
        assertRoundTrips("!@#$%^&*()-_=+[]{};:'\",.<>/?\\|`~")
    }

    func testSpaceIsMapped() {
        XCTAssertNotNil(KeyCodeMapper.keyStroke(for: " "))
    }

    /// The plainest combination must win. If a character is reachable unmodified, reporting it as
    /// needing Shift or Option would hold a modifier the real keyboard would not.
    func testUnmodifiedCharactersReportNoModifiers() {
        for character in "asdf" {
            guard let stroke = KeyCodeMapper.keyStroke(for: character) else { continue }
            XCTAssertFalse(stroke.shift, "'\(character)' should not need Shift")
            XCTAssertFalse(stroke.option, "'\(character)' should not need Option")
        }
    }

    func testUnmappableCharacterReturnsNil() {
        // Nothing on any keyboard types this directly, so the caller must get nil and fall back
        // rather than receive a wrong key.
        XCTAssertNil(KeyCodeMapper.keyStroke(for: "\u{1F512}"))
    }
}
