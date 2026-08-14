# UnlockMyMac

Unlock a Mac by proving that a paired Android phone or watch is nearby. The Android device acts as
a Bluetooth LE **peripheral**; a macOS menu-bar app acts as the central. When the Mac is locked and
sees a paired device, it opens a GATT connection and asks it to sign a random challenge. Only a
valid P-256 signature counts as proof of presence — never RSSI, never the BLE address, never the
advertised service UUID.

Both halves live here because they are one protocol. A change to the challenge format, the
characteristic layout, or the advertising rate has to land on both sides at once, and before this
was a monorepo those changes were separate commits in separate repositories with no way to bisect
them together.

## Layout

| Path | What it is |
|---|---|
| `android/` | Gradle project: `app` (phone), `wear` (Wear OS), `core` (shared BLE, crypto, settings) |
| `macos/` | macOS menu-bar central, built with XcodeGen + Xcode |
| `.github/workflows/` | CI for both halves |

Each half builds on its own; neither depends on the other's build system.

## Building

**Android** — `local.properties` needs an SDK path, then:

```sh
cd android
./gradlew :app:assembleDebug :wear:assembleDebug
./gradlew :core:test :core:lintDebug
```

**macOS** — the `.xcodeproj` is generated and deliberately not committed, so generate it first:

```sh
cd macos
xcodegen generate
xcodebuild -scheme MacBleUnlock build
```

## Security model

The detail lives next to the code it constrains — see [`android/README.md`](android/README.md) for
the threat model and the invariants both sides hold to, and [`macos/README.md`](macos/README.md) for
setup, calibration and the macOS-specific notes.

The short version: the BLE address is never identity, the signing key is generated in
`AndroidKeyStore` with no export path, challenges are single-use and time-boxed, and every GATT
rejection returns the same opaque code so a stranger learns nothing about which check failed. The
main residual risk is a **BLE relay attack** — there is no distance bounding here.

## Status

Working end to end: pairing, signed challenge-response, presence heartbeat, lock on absence, and
auto-unlock. Verified against a Galaxy Fold, a Galaxy A56 and a Galaxy Watch 4.

This is a personal, sideloaded project. It is not on any app store and takes a permission
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) that store policy would not allow.
