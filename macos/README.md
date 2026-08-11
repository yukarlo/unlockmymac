# MacBleUnlock (macOS)

Menu-bar companion app for the BLE Mac Unlock project.
This is the macOS *central* side: it scans for the paired Android phone's BLE advertisement and drives the presence state machine, GATT challenge-response, and lock/unlock actions.

The Android peripheral lives in a separate repo (`UnlockMyMac`). Status: **working end to end** —
zero-touch BLE pairing, signed challenge-response, presence heartbeat, lock on sustained absence,
and auto-unlock, verified against a Galaxy A56.

## Scope & Capabilities

- Native AppKit menu-bar ("accessory", no Dock icon) app built with Swift and SwiftUI, managed via [XcodeGen](https://github.com/yonaskolb/XcodeGen).
- **CoreBluetooth Central (`BLECentralManager`)**: Scans for the custom unlock service UUID (`BLEProtocol.serviceUUID`) and computes rolling-average RSSI (`RSSISmoother`).
- **Cryptographic GATT Challenge-Response (`ProtocolCodec`, `GATTChallengeClient`, `CryptoManager`)**: Writes structured UTF-8 challenge payloads (`mac-ble-unlock:v1...`) and verifies Android ECDSA P-256 (`SHA256withECDSA`) signatures against the paired X.509 SPKI DER public key using Swift Crypto (`CryptoKit`).
- **Pairing Management (`PairingManager`, `PairingWindowController`)**: Manages persistent `macInstallationId` and paired Android device records (`deviceId`, friendly name, DER public key), featuring an interactive SwiftUI Pairing window with QR code generation and registration import.
- **Presence State Machine (`PresenceStateMachine`)**: Manages proximity states (`absent`, `candidateNear`, `connecting`, `authenticating`, `authenticatedNear`, `unlockCooldown`), RSSI thresholds, auth timeouts (8s), heartbeat (10s), and absence grace windows (30s). Enforces one unlock per proximity cycle.
- **System Actions & Security (`SystemActionController`)**: Display wake (`caffeinate -u -t 2`), macOS session locking (`SACLockScreenImmediate` / fallback), and screen lock detection (`com.apple.screenIsLocked`).
- **Keychain & Auto-Unlock (`KeychainManager`, `AutoUnlockController`)**: Secure macOS password storage in system Keychain (`kSecClassGenericPassword`) and `CGEvent` keyboard injection to automatically submit password when Mac is locked and phone is authenticated (requires Accessibility permission).
- **Diagnostics & Status Menu (`EventLogger`, `DiagnosticsWindowController`, `SettingsWindowController`, `StatusMenuController`)**: Live status menu bar icon/menu, live event log window, proximity threshold calibration settings, pause/resume controls, and auto-unlock toggle.

## Project layout

```text
macos-app/
├─ project.yml                        # XcodeGen spec (source of truth for the Xcode project)
├─ Sources/MacBleUnlock/
│  ├─ main.swift                      # No-storyboard AppKit entry point
│  ├─ AppDelegate.swift               # App delegate owning singletons & status item
│  ├─ BLE/
│  │  ├─ BLEProtocol.swift            # Shared UUIDs + tunables
│  │  ├─ ProtocolCodec.swift          # Deterministic challenge payload codec
│  │  ├─ RSSISmoother.swift           # Rolling-average RSSI smoothing
│  │  ├─ BLECentralManager.swift      # CoreBluetooth central scanner & peripheral tracking
│  │  ├─ GATTChallengeClient.swift    # GATT transport & signature verification flow
│  │  └─ PresenceStateMachine.swift   # Presence state machine & unlock/lock orchestration
│  ├─ Crypto/
│  │  └─ CryptoManager.swift          # SecRandom challenge generation & P-256 signature verification
│  ├─ Pairing/
│  │  └─ PairingManager.swift         # Installation ID & paired device storage
│  ├─ Security/
│  │  ├─ SystemActionController.swift # Wake display, lock session, screen state observer
│  │  ├─ KeychainManager.swift        # macOS login password Keychain storage
│  │  └─ AutoUnlockController.swift   # Accessibility permission check & CGEvent password entry
│  ├─ Diagnostics/
│  │  └─ EventLogger.swift            # Thread-safe in-memory diagnostic logger
│  ├─ UI/
│  │  ├─ StatusMenuController.swift   # Status-bar menu reflecting live state & window triggers
│  │  ├─ PairingWindowController.swift# SwiftUI pairing window & QR code generator
│  │  ├─ DiagnosticsWindowController.swift # SwiftUI live diagnostic log window
│  │  └─ SettingsWindowController.swift   # SwiftUI proximity calibration & password settings
│  └─ Resources/
│     └─ Info.plist                   # LSUIElement=true, NSBluetoothAlwaysUsageDescription
```

`MacBleUnlock.xcodeproj` is generated via XcodeGen (`xcodegen generate`).

## Build & run

Requires [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```sh
cd macos-app
xcodegen generate
open MacBleUnlock.xcodeproj
```

Build & run (⌘R) from Xcode. On first launch, macOS will prompt for Bluetooth permission — approve it so the app can scan.

## Setup

1. **Pair.** Turn on *Discoverable by Mac* in the Android app, then open **Pair Android Device**
   here and scan the QR with the phone. The Mac writes the QR's token to the pairing
   characteristic and reads the phone's identity back over BLE — no manual key entry. (A manual
   import fallback remains in the pairing window.)
2. **Calibrate the near threshold.** The Android peripheral advertises at low transmit power, so
   a phone on the desk typically reads around −73 dBm against the −60 dBm default. Set **Near
   RSSI** in Settings to roughly −80 dBm or the state machine will never treat the phone as a
   candidate.
3. **Auto-unlock (optional, off by default).** Grant Accessibility, store the login password in
   Settings, then enable *Auto-Unlock* from the menu bar.

## Notes learned the hard way

- **Scan duplicates stay enabled in every state.** Absence is inferred from `lastSeenAt`, which
  only advances on a `didDiscover` callback. Suppressing duplicates freezes it, the 30 s stale
  sweep then drops a phone sitting right there, and the Mac locks itself and re-authenticates on
  a loop.
- **Never send Space to wake the lock screen.** `wakeDisplay()` has already woken it, so the
  password field is focused and the Space is inserted as a leading character — the password is
  then silently wrong. `AutoUnlockController` sends Escape, clears the field (⌘A + Delete), and
  types with physical `CGKeyCode`s plus explicit Shift events rather than Unicode string events.
- **One unlock attempt per lock session.** The 10 s heartbeat would otherwise retype the password
  indefinitely after a failure, which macOS throttles. Re-armed on the screen lock/unlock
  notifications, with a 30 s floor as a backstop.
- **GATT status `0x80`** from the phone means "waiting for the user to approve"; retry within the
  auth timeout rather than treating it as a read failure.

## Security notes

- The advertised service UUID is public and is used for discovery only. Trust comes solely from
  a P-256 signature over a challenge naming this Mac's installation ID and the paired device ID.
- The paired record (device ID, name, SPKI DER public key) is stored in the Keychain, not
  UserDefaults — integrity matters here, since anything that can rewrite that record owns the
  unlock.
- **This does not defend against BLE relay attacks.** There is no distance bounding. An attacker
  who forwards traffic between the phone and the Mac defeats challenge-response entirely.
- Auto-unlock stores your login password for replay as keystrokes. That is the highest-risk part
  of this design; wake-on-approach plus lock-on-absence delivers most of the value without it.

Command-line build:

```sh
xcodebuild -project MacBleUnlock.xcodeproj -scheme MacBleUnlock \
  -configuration Debug -destination 'platform=macOS' \
  CODE_SIGNING_ALLOWED=NO build
```
