import AppKit
import SwiftUI

struct SettingsView: View {
    @ObservedObject var stateMachine: PresenceStateMachine
    @ObservedObject var autoUnlockController: AutoUnlockController

    @State private var passwordInput: String = ""
    @State private var hasStoredPassword: Bool = KeychainManager.hasPassword()
    @State private var statusMessage: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Section: Proximity Calibration
                VStack(alignment: .leading, spacing: 8) {
                    Text("Proximity Calibration")
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.bottom, 4)

                    HStack {
                        Text("Near Threshold (dBm):")
                        Spacer()
                        Text("\(Int(stateMachine.nearRSSIThreshold)) dBm")
                            .bold()
                    }
                    Slider(value: $stateMachine.nearRSSIThreshold, in: -90...(-40), step: 1)
                    Text("The Bluetooth signal strength required to trigger auto-unlock. Higher values (closer to -40 dBm) require the phone to be closer to your Mac.")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    HStack {
                        Text("Signal Loss Grace:")
                        Spacer()
                        Text("\(Int(stateMachine.absenceTimeoutSeconds)) seconds")
                            .bold()
                    }
                    Slider(value: $stateMachine.absenceTimeoutSeconds, in: 5...60, step: 1)
                    Text("How long a gap in the phone's signal is tolerated before giving up and re-acquiring. Does not lock the Mac.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Divider()

                // Section: Auto-Unlock Credentials
                VStack(alignment: .leading, spacing: 12) {
                    Text("Auto-Unlock Credentials")
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.bottom, 4)

                    Toggle("Enable Auto-Unlock on Proximity", isOn: $autoUnlockController.isEnabled)
                        .bold()

                    HStack {
                        Text("Accessibility Permission:")
                        Spacer()
                        Text(autoUnlockController.isAccessibilityAuthorized ? "Granted" : "Not Granted")
                            .foregroundColor(autoUnlockController.isAccessibilityAuthorized ? .green : .red)
                            .bold()

                        if !autoUnlockController.isAccessibilityAuthorized {
                            Button("Grant") {
                                autoUnlockController.promptAccessibilityPermission()
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text(hasStoredPassword ? "Update macOS Password in Keychain:" : "Save macOS Password in Keychain:")
                            .font(.caption)

                        SecureField("macOS Password", text: $passwordInput)
                            .textFieldStyle(.roundedBorder)

                        HStack {
                            Button("Save Password") {
                                if KeychainManager.savePassword(passwordInput) {
                                    hasStoredPassword = true
                                    passwordInput = ""
                                    statusMessage = "Password saved in Keychain"
                                }
                            }
                            .disabled(passwordInput.isEmpty)

                            if hasStoredPassword {
                                Button("Delete Password", role: .destructive) {
                                    KeychainManager.deletePassword()
                                    hasStoredPassword = false
                                    statusMessage = "Password removed from Keychain"
                                }
                            }
                        }

                        if let statusMessage {
                            Text(statusMessage)
                                .font(.caption)
                                .foregroundColor(.green)
                        }
                    }
                }
            }
            .padding()
        }
        .frame(width: 440, height: 460)
        .onAppear {
            autoUnlockController.checkAccessibilityPermission()
        }
    }
}

final class SettingsWindowController: NSWindowController {
    convenience init(stateMachine: PresenceStateMachine, autoUnlockController: AutoUnlockController) {
        let hostingView = NSHostingView(rootView: SettingsView(stateMachine: stateMachine, autoUnlockController: autoUnlockController))
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 460),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "BLE Mac Unlock Settings"
        window.contentView = hostingView
        window.center()
        self.init(window: window)
    }
}
