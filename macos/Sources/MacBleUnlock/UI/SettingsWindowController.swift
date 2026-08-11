import AppKit
import SwiftUI

struct SettingsView: View {
    @ObservedObject var stateMachine: PresenceStateMachine
    @ObservedObject var autoUnlockController: AutoUnlockController

    @State private var passwordInput: String = ""
    @State private var hasStoredPassword: Bool = KeychainManager.hasPassword()
    @State private var statusMessage: String?

    var body: some View {
        Form {
            Section(header: Text("Proximity Calibration").font(.headline)) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Near Threshold (dBm):")
                        Spacer()
                        Text("\(Int(stateMachine.nearRSSIThreshold)) dBm")
                            .bold()
                    }
                    Slider(value: $stateMachine.nearRSSIThreshold, in: -90...(-40), step: 1)

                    HStack {
                        Text("Far Threshold (dBm):")
                        Spacer()
                        Text("\(Int(stateMachine.farRSSIThreshold)) dBm")
                            .bold()
                    }
                    Slider(value: $stateMachine.farRSSIThreshold, in: -100...(-50), step: 1)

                    HStack {
                        Text("Absence Grace Window:")
                        Spacer()
                        Text("\(Int(stateMachine.absenceTimeoutSeconds)) seconds")
                            .bold()
                    }
                    Slider(value: $stateMachine.absenceTimeoutSeconds, in: 5...60, step: 1)
                }
                .padding(.vertical, 4)
            }

            Divider()

            Section(header: Text("Auto-Unlock Credentials").font(.headline)) {
                VStack(alignment: .leading, spacing: 12) {
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
                .padding(.vertical, 4)
            }
        }
        .padding()
        .frame(width: 440, height: 420)
        .onAppear {
            autoUnlockController.checkAccessibilityPermission()
        }
    }
}

final class SettingsWindowController: NSWindowController {
    convenience init(stateMachine: PresenceStateMachine, autoUnlockController: AutoUnlockController) {
        let hostingView = NSHostingView(rootView: SettingsView(stateMachine: stateMachine, autoUnlockController: autoUnlockController))
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 420),
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
