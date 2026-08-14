# UnlockMyMac — Android peripheral

Android half of a personal BLE Mac-unlock companion. The phone acts as a Bluetooth LE
**peripheral**; a macOS menu-bar app (not in this repo yet) acts as the central. When the Mac
sees the phone nearby it opens a GATT connection and asks it to sign a random challenge. Only a
valid P-256 signature counts as proof of presence — never RSSI, never the BLE MAC address, never
the advertised service UUID.

Status: **Working end to end** against the macOS central (separate repo, `UnlockFromDroid/macos-app`).
Verified on a Galaxy A56 (Android 15) + MacBook: pairing, signed challenge-response, presence
heartbeat, lock on absence, and auto-unlock.

---

## Threat model

### Protects against

- A stranger advertising the same service UUID: the UUID is a discovery hint, not a credential.
- Replay of an observed challenge: each challenge is single-use, time-boxed, and remembered.
- Unlock decisions driven by unstable RSSI: the phone signs a structured payload or nothing.
- A second central racing an in-flight authentication: one session at a time, others refused.

### Does *not* protect against

- **BLE relay attacks.** An attacker who forwards traffic between the real phone and the Mac
  wins. There is no distance bounding here. This is the main residual risk.
- Compromise of the phone or of the logged-in Mac account.
- The inherent risk of automating macOS credential entry (a macOS-side concern).

### Invariants this code holds to

- The BLE MAC address is never used as identity. Connection state is keyed by address purely as
  transport bookkeeping; identity comes only from a signature naming this phone's device id and
  the paired Mac's installation id.
- The private key is generated in `AndroidKeyStore` and has no export path.
- Challenges, signatures, and key material are never logged. The event log refers to a challenge
  by the first four bytes of its SHA-256 instead.
- Every GATT rejection returns the same opaque code, so a stranger learns nothing about which
  check failed.

---

## Protocol

Service `f9a2b8e3-54cd-4e92-a123-765432198765`, advertised connectable.

| Characteristic | Properties | Purpose |
|---|---|---|
| `…766` challenge | WRITE | Mac writes the payload it wants signed |
| `…767` response | READ | Mac reads the DER ECDSA signature |
| `…768` pairing | WRITE + READ | Token in, identity out — pairing window only |

### Challenge payload

```
mac-ble-unlock:v1
macInstallationId=<uuid>
deviceId=<uuid>
issuedAt=<unix-ms>
challenge=<base64url-32-bytes>
```

Exactly five LF-separated lines, fixed key order, no CR, no trailing newline, no surrounding
whitespace. The phone signs **the exact bytes it received** with `SHA256withECDSA`; parsing is
for validation only. Any re-serialisation would break verification on the Mac.

Rejected when: the grammar does not match · either identifier is not a UUID · the
`macInstallationId` is not the paired Mac · the `deviceId` is not ours · the challenge is not
exactly 32 bytes · `issuedAt` is more than 120 s from our clock · the challenge was seen before ·
another central holds a live session · the write is prepared or has a non-zero offset.

### Pairing

Plan §7 left the phone→Mac direction undefined, so this implementation adds a fourth
characteristic instead of a second QR code:

1. The Mac shows a QR containing its installation id, a random token, a friendly name, and an
   expiry.
2. The user scans it here, which opens a **60-second** window (never outliving the QR's own
   expiry).
3. The Mac writes `mac-ble-pair:v1` with the same token to `…768`.
4. The Mac reads `…768` and gets back `deviceId`, a friendly name, and the X.509 SPKI DER public
   key. That first read commits the pairing and closes the window.

Outside the window every write to `…768` is refused, so a copied service UUID has nothing to talk
to. The pairing token is a pairing secret only — holding it never authenticates an unlock.

### GATT status codes

| Code | Meaning |
|---|---|
| `0x80` | Awaiting user approval. Retry within your auth timeout. |
| `0x81` | Rejected (opaque on purpose). |
| `0x07` | Invalid read offset. |

`0x80` exists because "Approve every request" needs human-scale time. The challenge TTL is 10 s
normally and **60 s** when approval is required.

### Read-blob handling

The signature (~70 B) and the pairing response (~200 B) exceed the default 23-byte ATT MTU. The
signature is computed once on the `offset == 0` read and cached for continuations — ECDSA is
randomised, so signing per fragment would return a spliced blob that cannot verify.

### Advertising lifecycle

A connectable advertisement is stopped by the Bluetooth controller the instant a central
connects, and the Android stack never resumes it. The service therefore restarts advertising
500 ms after the last central disconnects (`GattServerListener.onConnectedCentralsChanged`).

Without this the phone is discoverable exactly once per app start: the Mac's first connection
makes it invisible for good, while the Mac keeps reconnecting on its retained peripheral handle
— so the failure presents as "authentication works but discovery is dead". The Home screen shows
**Connected (advertising paused)** for the duration of a connection rather than claiming to be
discoverable when the radio is not.

Likewise, `serviceEnabled` is persisted in DataStore but `serviceRunning` is in-memory. After an
app reinstall or force stop the switch would read ON with nothing running, so the Home screen
reconciles the two on resume and restarts the service. A reboot is handled without opening the
app: `BootReceiver` starts the service from `BOOT_COMPLETED`, registered in both the phone and
watch manifests.

---

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_ADVERTISE` | Advertise as a peripheral |
| `BLUETOOTH_CONNECT` | Run the GATT server, respond to centrals |
| `FOREGROUND_SERVICE` + `..._CONNECTED_DEVICE` | Stay alive with the screen off |
| `POST_NOTIFICATIONS` | Ongoing status + approval prompts (optional; the in-app card still works) |
| `CAMERA` | Scan the Mac's pairing QR. Requested only on the pairing screen. |

**Not requested:** `BLUETOOTH_SCAN` and `ACCESS_FINE_LOCATION`. A peripheral never scans, so
neither is needed. `minSdk` is 31, which avoids the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` path.

---

## Using it

1. **Grant permissions** on the Home screen, then turn on *Discoverable by Mac*. A low-importance
   ongoing notification appears.
2. **Pair.** Start pairing on the Mac, tap *Pair with a Mac* here, scan the QR. Keep the screen
   open for the 60-second window. On success, compare the shown SHA-256 fingerprint with the one
   on the Mac before trusting it.
3. **Calibrate on the Mac side.** RSSI thresholds live in the macOS app; nothing here needs tuning
   for distance. Note that this app advertises at `ADVERTISE_TX_POWER_LOW` (−15 dBm), so observed
   RSSI runs 10–15 dBm weaker than the plan's −60 dBm default assumes — expect to set the Mac's
   near threshold around −75 to −80 dBm, or raise the transmit power in `BleAdvertiser`.

### Controls

- **Pause** — stops advertising and closes the GATT server but keeps the pairing and the service
  alive, so resuming is instant. Use this when you leave the laptop unattended somewhere risky.
- **Discoverable by Mac** off — stops the service entirely.
- **Approve every request** — every challenge waits for a tap on the notification or the in-app
  card. Blocks unattended unlocks at the cost of the overnight/screen-off use case.
- **Balanced advertising** — faster discovery, more battery. Default is low power.
- **Forget this Mac** (Diagnostics) — unpairs, regenerates the device id, and **destroys the
  identity key**, so the public key the Mac still holds becomes useless.

### Diagnostics

Public key fingerprint, key storage level (StrongBox / hardware-backed / software), truncated
device id, and a 200-entry event log persisted to `filesDir/events.json`.

---

## Layout

```
app/src/main/java/com/yukarlo/unlockmymac/
├─ ble/          UUIDs, advertiser, GATT server, codecs, session state, replay cache
├─ crypto/       AndroidKeyStore P-256 signer
├─ data/         DataStore settings + pairing, in-memory status bridge, event log
├─ pairing/      Pairing window coordinator
├─ permissions/  Runtime permission helpers
├─ service/      Foreground service, notifications, approval receiver
├─ ui/           Compose Home / Pairing / Diagnostics
└─ util/         Redaction (challenge tags, fingerprints), lifecycle helper
```

The codecs (`ChallengeCodec`, `PairingCodec`, `ReplayCache`, `ChallengeSessions`) are pure Kotlin
with no Android dependencies, which is where all the security rules live and where they are
tested. `AppContainer` is a hand-written service locator — the graph is six singletons.

---

## Building and testing

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest          # 67 tests: codecs, replay, sessions, vectors
./gradlew :app:connectedDebugAndroidTest  # Keystore sign/verify; needs a device
./gradlew :app:lintDebug
```

`app/src/test/resources/protocol-vectors.json` holds shared fixtures — service UUIDs, status
codes, timing constants, a throwaway P-256 keypair, and a real signature over a canonical
payload. `ProtocolVectorsTest` runs them, so changing the wire format without updating the
vectors fails the build. The macOS app should consume the same file:

```swift
let key = try P256.Signing.PublicKey(derRepresentation: pairedPublicKeyDER)
let signature = try P256.Signing.ECDSASignature(derRepresentation: signatureData)
let isValid = key.isValidSignature(signature, for: activePayload)
```

Use `derRepresentation`, not `rawRepresentation` — Android exports X.509 SubjectPublicKeyInfo.
Note that UUIDs round-trip case-insensitively, because Swift uppercases them and Android does not.

### Manual verification without a Mac

Use nRF Connect (or another Android phone) as the central:

- Scan → the service is visible and connectable with the phone's screen off and the app
  backgrounded.
- Write a valid payload to `…766`, read `…767`, and verify offline:
  ```bash
  openssl dgst -sha256 -verify pub.pem -signature sig.der payload.bin
  ```
  where `pub.pem` comes from the key read out of `…768`.

Security checks worth repeating by hand: read `…767` with no prior write (`0x81`), read twice
(`0x81`), let the TTL lapse (`0x81`), write the same challenge twice (`0x81`), connect two
centrals and write from both (`0x81` for the second), read at an offset past the signature
(`0x07`), and write to `…768` with no window open or a wrong token (`0x81`).

Reliability: toggle Bluetooth off and on with the service running (advertising must resume),
force-stop and relaunch, reboot, and leave it advertising overnight with the screen off.

---

## Not implemented

- **Home Wi-Fi SSID gating** (plan §5.6). Would need `ACCESS_FINE_LOCATION` /
  `NEARBY_WIFI_DEVICES`; deliberately out of scope.
- **BiometricPrompt before signing** (plan §5.3, optional). It would break screen-off operation.
  "Approve every request" covers the same intent without a biometric dependency.
- Everything macOS: the central, RSSI smoothing, the presence state machine, wake, lock, and
  auto-unlock (plan §6 and Milestones 4–6).
