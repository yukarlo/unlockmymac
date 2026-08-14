import AppKit
import CoreImage.CIFilterBuiltins
import CoreBluetooth
import SwiftUI

/// SwiftUI View for managing Android device pairing.
struct PairingView: View {
    @ObservedObject var pairingManager: PairingManager
    @ObservedObject var bleCentral: BLECentralManager

    @State private var inputDeviceId: String = ""
    @State private var inputDeviceName: String = ""
    @State private var inputPublicKeyBase64: String = ""
    @State private var statusMessage: String?
    @State private var isError: Bool = false
    @State private var isEnrolmentInFlight = false
    @State private var isShowingPairingQR = false
    @State private var scanWasRunningBeforeWindow = false
    @State private var isBlePairingInFlight: Bool = false
    @State private var cachedQRImage: NSImage?

    /// Consecutive failed auto-pairing attempts, and when the last one was tried.
    ///
    /// `onChange(of: discoveredPeripherals)` fires on every advertisement — duplicates are on so the
    /// scan can tell a live handle from a rotated-away one — so without these the moment a failure
    /// cleared `isBlePairingInFlight` the next advertisement started another attempt. Measured with
    /// both sides unpaired: connect, write claim, rejected, disconnect, reconnect every ~1.6s, 90
    /// rejections and still going, both radios busy for nothing. The phone was answering
    /// `pairing_window_closed` every time, which is not transient and will not improve by asking
    /// faster.
    @State private var blePairingFailures: Int = 0
    @State private var lastBlePairingAttempt: Date?
    @State private var blePairingStartedAt: Date?

    private let gattPairingClient: GATTPairingClient
    private let gattEnrolmentClient: GATTEnrolmentClient

    init(pairingManager: PairingManager, bleCentral: BLECentralManager) {
        self.pairingManager = pairingManager
        self.bleCentral = bleCentral
        self.gattPairingClient = GATTPairingClient(bleCentral: bleCentral, pairingManager: pairingManager)
        self.gattEnrolmentClient = GATTEnrolmentClient(bleCentral: bleCentral, pairingManager: pairingManager)
    }

    var body: some View {
        VStack(spacing: 20) {
            Text("Android Device Pairing")
                .font(.headline)

            if !pairingManager.pairedDevices.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text(pairingManager.pairedDevices.count == 1 ? "Paired Device" : "Paired Devices")
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    // One row per device, each independently removable: revoking a lost watch
                    // must not force re-pairing the phone.
                    ForEach(pairingManager.pairedDevices, id: \.deviceId) { paired in
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                                .font(.title2)
                            VStack(alignment: .leading) {
                                Text(paired.name)
                                    .font(.title3)
                                    .bold()
                                Text(paired.deviceId)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                Text("Paired On: \(paired.pairedAt.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) {
                                pairingManager.unpair(deviceId: paired.deviceId)
                                cachedQRImage = nil
                                statusMessage = "Forgot \(paired.name)"
                                isError = false
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.borderless)
                        }
                        Divider()
                    }

                    Spacer()

                    // A phone has a camera, so it pairs the ordinary way: show the QR again.
                    Button {
                        isShowingPairingQR = true
                        cachedQRImage = nil
                        setupPairingSession()
                        statusMessage = "Scan the code with the new phone"
                        isError = false
                    } label: {
                        Label("Pair another phone", systemImage: "qrcode")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isShowingPairingQR)

                    // A watch has no camera, so an already-paired device signs for it instead and
                    // this reads that signature over BLE.
                    Button {
                        addVouchedDevice()
                    } label: {
                        Label("Add a companion device", systemImage: "applewatch")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isEnrolmentInFlight)

                    Button(role: .destructive) {
                        pairingManager.unpairAll()
                        cachedQRImage = nil
                        statusMessage = "All devices unpaired"
                        isError = false
                    } label: {
                        Label("Unpair All Devices", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)

                    if let statusMessage {
                        Text(statusMessage)
                            .font(.caption)
                            .foregroundColor(isError ? .red : .secondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
                .padding()
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(NSColor.controlBackgroundColor)))
            }

            if pairingManager.pairedDevices.isEmpty || isShowingPairingQR {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("1. Scan QR Code from Android App")
                            .font(.subheadline)
                            .bold()

                        if let qrImage = cachedQRImage {
                            Image(nsImage: qrImage)
                                .resizable()
                                .interpolation(.none)
                                .scaledToFit()
                                // A QR that scales with the window beats one cropped by it; below
                                // about 140pt a phone camera starts struggling, so hold that floor.
                                .frame(minWidth: 140, idealWidth: 180, maxWidth: 240,
                                       minHeight: 140, idealHeight: 180, maxHeight: 240)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.white)
                                .cornerRadius(8)
                        } else {
                            ProgressView()
                                .frame(maxWidth: .infinity, minHeight: 180)
                        }

                        HStack {
                            ProgressView()
                                .controlSize(.small)
                            Text(isBlePairingInFlight ? "Completing BLE pairing exchange…" : "Waiting for phone to scan QR code…")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .center)

                        Divider()

                        Text("2. Manual Import (Fallback)")
                            .font(.subheadline)
                            .bold()

                        VStack(alignment: .leading, spacing: 8) {
                            Text("Device Name:")
                                .font(.caption)
                            TextField("e.g. Pixel 8 Pro", text: $inputDeviceName)
                                .textFieldStyle(.roundedBorder)

                            Text("Android Device ID:")
                                .font(.caption)
                            TextField("Device UUID", text: $inputDeviceId)
                                .textFieldStyle(.roundedBorder)

                            Text("Public Key DER (Base64):")
                                .font(.caption)
                            TextEditor(text: $inputPublicKeyBase64)
                                .font(.system(.caption, design: .monospaced))
                                .frame(height: 70)
                                .border(Color.gray.opacity(0.3))
                        }

                        Button {
                            completeManualPairing()
                        } label: {
                            Text("Complete Pairing Manually")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(inputDeviceName.isEmpty || inputDeviceId.isEmpty || inputPublicKeyBase64.isEmpty)

                        if let statusMessage {
                            Text(statusMessage)
                                .font(.caption)
                                .foregroundColor(isError ? .red : .green)
                        }
                    }
                }
                .onAppear {
                    setupPairingSession()
                }
                .onChange(of: bleCentral.discoveredPeripherals) { peripherals in
                    attemptAutoBlePairing(peripherals: peripherals)
                }
            }
        }
        .padding()
        .frame(minWidth: 420, idealWidth: 460, minHeight: 420, idealHeight: 560)
        .onAppear {
            // Scanning normally runs only while the Mac is locked, which it never is while this
            // window is in front of you — so without this nothing is ever discovered and both
            // pairing and enrolment sit there finding no devices.
            scanWasRunningBeforeWindow = bleCentral.isScanning
            bleCentral.start()
        }
        .onDisappear {
            // Release any exchange still in flight. Both hold the peripheral *and* the single
            // `bleCentral.connectionDelegate` slot, so closing the window mid-exchange used to block
            // GATTChallengeClient outright until the 10-15s timeout — and locking the screen right
            // after closing this window is the obvious thing to do.
            gattPairingClient.cancel()
            gattEnrolmentClient.cancel()
            if !scanWasRunningBeforeWindow { bleCentral.stop() }
            isShowingPairingQR = false
        }
    }

    private func setupPairingSession() {
        guard cachedQRImage == nil else { return }
        // A fresh session is the user asking again, so the give-up counter starts over. Without
        // this, pressing Pair after three failures would do nothing at all.
        blePairingFailures = 0
        lastBlePairingAttempt = nil
        blePairingStartedAt = nil
        if let jsonString = pairingManager.startPairingSession() {
            cachedQRImage = generateQRCodeImage(from: jsonString)
        }
    }

    /// Asks an already-paired device whether it is vouching for another one.
    ///
    /// Reads from whichever paired device is currently in range: the offer names the Mac and is
    /// signed, so a device that has nothing staged simply answers "nothing to enrol" and the next
    /// one is tried. Nothing is trusted because of which peripheral answered.
    private func addVouchedDevice() {
        guard !isEnrolmentInFlight else { return }
        isEnrolmentInFlight = true
        isError = false
        statusMessage = "Looking for a paired device…"

        DispatchQueue.main.asyncAfter(deadline: .now() + Self.enrolmentScanSeconds) {
            let candidates = bleCentral.discoveredPeripherals.values
                .sorted { ($0.averageRSSI ?? -200) > ($1.averageRSSI ?? -200) }
                .map(\.peripheral)

            guard !candidates.isEmpty else {
                isEnrolmentInFlight = false
                statusMessage = "No paired device is in range"
                isError = true
                EventLogger.shared.warning(category: "Pairing", "No device in range to read an enrolment offer from")
                return
            }

            askNextCandidateForOffer(candidates, index: 0)
        }
    }

    /// Asks each device in range in turn until one is vouching for something.
    ///
    /// Strongest-first only, which is what this used to do, is wrong as soon as more than one of our
    /// peripherals is around: the offer is staged on the phone, but a watch on the wrist is the
    /// stronger signal, so the Mac asked the watch and got nothing. Measured with an offer live on
    /// the phone — four reads, all against the wrong peripheral, and no outcome logged either.
    ///
    /// Trying every candidate is safe because the offer names this Mac and is signed: a device with
    /// nothing staged simply answers "nothing to enrol", and nothing is trusted on the basis of
    /// which peripheral happened to answer.
    private func askNextCandidateForOffer(_ candidates: [CBPeripheral], index: Int) {
        guard index < candidates.count else {
            isEnrolmentInFlight = false
            statusMessage = "No device in range is vouching for anything. Send the key from the watch, then try again."
            isError = true
            EventLogger.shared.warning(
                category: "Pairing",
                "Asked \(candidates.count) device(s) for an enrolment offer; none had one staged"
            )
            return
        }

        let target = candidates[index]
        statusMessage = "Asking \(target.name ?? "device \(index + 1) of \(candidates.count)") if it is vouching for anything…"

        gattEnrolmentClient.readOffer(from: target) { result in
            switch result {
            case .success(let device):
                isEnrolmentInFlight = false
                statusMessage = "Added \(device.name)"
                isError = false
                EventLogger.shared.info(category: "Pairing", "Enrolled '\(device.name)' from a vouched offer")
            case .failure(let error):
                // Log every attempt: the read outcome was previously invisible, so a failure looked
                // like the button doing nothing at all.
                EventLogger.shared.info(
                    category: "Pairing",
                    "No offer from \(target.identifier.uuidString): \(error.description)"
                )
                askNextCandidateForOffer(candidates, index: index + 1)
            }
        }
    }

    /// Long enough for a phone advertising once a second in low-power mode to be heard.
    private static let enrolmentScanSeconds: TimeInterval = 3

    /// Refusals before auto-pairing gives up and waits for the user.
    ///
    /// Counts only answers from the device — a claim it actually rejected. Transport failures
    /// (timeout, failed connect, dropped link) do not count, because they say nothing about whether
    /// the device would accept: measured re-pairing, the Mac wrote its claim 0.9s before the phone
    /// opened its pairing window, then the next two attempts timed out on the connect itself, and a
    /// flat three-strike cap gave up on a pairing that was moments from working. `blePairingBudget`
    /// bounds those instead, so a genuinely absent device still stops the loop.
    private static let maxBlePairingFailures = 3

    /// How long to keep retrying past transport failures before giving up.
    private static let blePairingBudget: TimeInterval = 90

    /// Minimum gap between auto-pairing attempts.
    ///
    /// A phone in low-power mode advertises about once a second, so without this the retry rate is
    /// set by the advertising interval rather than by anything meaningful.
    private static let blePairingRetryInterval: TimeInterval = 5

    private func attemptAutoBlePairing(peripherals: [UUID: DiscoveredPeripheral]) {
        // Freshest handle that has advertised since we last hung up on it, not an arbitrary dictionary
        // entry. `values.first` has no defined order, so a stale handle sitting alongside a live one
        // could be picked over and over — and each pick costs a full connect timeout to learn nothing.
        let target = peripherals.values
            .filter(\.heardSinceDisconnect)
            .max(by: { $0.lastSeenAt < $1.lastSeenAt })

        guard !isBlePairingInFlight,
              pairingManager.pairedDevices.isEmpty || isShowingPairingQR,
              let token = pairingManager.activePairingToken,
              let targetPeripheral = target?.peripheral else { return }

        guard blePairingFailures < Self.maxBlePairingFailures else { return }

        if blePairingStartedAt == nil { blePairingStartedAt = Date() }
        if let started = blePairingStartedAt,
           Date().timeIntervalSince(started) > Self.blePairingBudget {
            return
        }

        if let last = lastBlePairingAttempt,
           Date().timeIntervalSince(last) < Self.blePairingRetryInterval {
            return
        }

        lastBlePairingAttempt = Date()
        isBlePairingInFlight = true
        statusMessage = "Device detected over BLE! Pairing…"
        isError = false

        gattPairingClient.pair(peripheral: targetPeripheral, token: token) { result in
            isBlePairingInFlight = false
            switch result {
            case .success(let paired):
                statusMessage = "Paired successfully with '\(paired.name)'!"
                isError = false
                isShowingPairingQR = false
                cachedQRImage = nil
                blePairingFailures = 0
            case .failure(let err):
                isError = true
                // Only a refusal from the device counts. Everything the transport can throw at us
                // is bounded by `blePairingBudget` instead.
                let nsError = err as NSError
                let isTransport = nsError.domain == "GATTPairingClient"
                    && [-1, -2, -3].contains(nsError.code)
                if !isTransport { blePairingFailures += 1 }

                let outOfBudget = blePairingStartedAt.map {
                    Date().timeIntervalSince($0) > Self.blePairingBudget
                } ?? false

                if blePairingFailures >= Self.maxBlePairingFailures || outOfBudget {
                    // Say what to do rather than keep hammering. The overwhelmingly common cause is
                    // that the device has no pairing screen open, which only the user can change.
                    statusMessage = "Could not pair. Open the pairing screen on the device first, then press Pair again."
                    EventLogger.shared.warning(
                        category: "Pairing",
                        "Auto-pairing gave up (\(blePairingFailures) refusal(s), budget \(outOfBudget ? "spent" : "left")): \(err.localizedDescription)"
                    )
                } else {
                    statusMessage = "BLE pairing attempt failed: \(err.localizedDescription)"
                }
            }
        }
    }

    private func generateQRCodeImage(from qrString: String) -> NSImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(qrString.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage,
              let cgImage = context.createCGImage(outputImage, from: outputImage.extent) else {
            return nil
        }
        return NSImage(cgImage: cgImage, size: NSSize(width: 180, height: 180))
    }

    private func completeManualPairing() {
        guard let derData = Data(base64Encoded: inputPublicKeyBase64.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            statusMessage = "Invalid Base64 public key DER format"
            isError = true
            return
        }

        pairingManager.pair(
            deviceId: inputDeviceId.trimmingCharacters(in: .whitespacesAndNewlines),
            name: inputDeviceName.trimmingCharacters(in: .whitespacesAndNewlines),
            publicKeyDER: derData
        )
        statusMessage = "Pairing successful!"
        isError = false
    }
}

/// Window controller managing the Pairing dialog window.
final class PairingWindowController: NSWindowController {
    convenience init(pairingManager: PairingManager, bleCentral: BLECentralManager) {
        let hostingView = NSHostingView(rootView: PairingView(pairingManager: pairingManager, bleCentral: bleCentral))
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 520),
            // Resizable because the content is not a fixed height: with devices paired the list
            // grows a row each time, and the QR section can appear below it.
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Pair Android Device"
        window.contentMinSize = NSSize(width: 420, height: 420)
        window.contentView = hostingView
        window.center()
        self.init(window: window)
    }
}
