import AppKit
import CoreImage.CIFilterBuiltins
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
    @State private var isBlePairingInFlight: Bool = false
    @State private var cachedQRImage: NSImage?

    private let gattPairingClient: GATTPairingClient

    init(pairingManager: PairingManager, bleCentral: BLECentralManager) {
        self.pairingManager = pairingManager
        self.bleCentral = bleCentral
        self.gattPairingClient = GATTPairingClient(bleCentral: bleCentral, pairingManager: pairingManager)
    }

    var body: some View {
        VStack(spacing: 20) {
            Text("Android Device Pairing")
                .font(.headline)

            if let paired = pairingManager.pairedDevice {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                            .font(.title2)
                        VStack(alignment: .leading) {
                            Text("Paired Device")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Text(paired.name)
                                .font(.title3)
                                .bold()
                        }
                    }

                    Divider()

                    Text("Device ID: \(paired.deviceId)")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Text("Paired On: \(paired.pairedAt.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Spacer()

                    Button(role: .destructive) {
                        pairingManager.unpair()
                        cachedQRImage = nil
                        statusMessage = "Device unpaired"
                        isError = false
                    } label: {
                        Label("Unpair Device", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                }
                .padding()
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(NSColor.controlBackgroundColor)))
            } else {
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
                                .frame(width: 180, height: 180)
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
        .frame(width: 420, height: 520)
    }

    private func setupPairingSession() {
        guard cachedQRImage == nil else { return }
        if let jsonString = pairingManager.startPairingSession() {
            cachedQRImage = generateQRCodeImage(from: jsonString)
        }
    }

    private func attemptAutoBlePairing(peripherals: [UUID: DiscoveredPeripheral]) {
        guard !isBlePairingInFlight,
              pairingManager.pairedDevice == nil,
              let token = pairingManager.activePairingToken,
              let targetPeripheral = peripherals.values.first?.peripheral else { return }

        isBlePairingInFlight = true
        statusMessage = "Device detected over BLE! Pairing…"
        isError = false

        gattPairingClient.pair(peripheral: targetPeripheral, token: token) { result in
            isBlePairingInFlight = false
            switch result {
            case .success(let paired):
                statusMessage = "Paired successfully with '\(paired.name)'!"
                isError = false
            case .failure(let err):
                statusMessage = "BLE pairing attempt failed: \(err.localizedDescription)"
                isError = true
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
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Pair Android Device"
        window.contentView = hostingView
        window.center()
        self.init(window: window)
    }
}
