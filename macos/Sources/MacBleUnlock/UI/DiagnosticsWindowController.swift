import AppKit
import SwiftUI

struct DiagnosticsView: View {
    @ObservedObject var logger = EventLogger.shared
    @ObservedObject var stateMachine: PresenceStateMachine
    @ObservedObject var bleCentral: BLECentralManager
    @ObservedObject var pairingManager: PairingManager

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Header stats
            HStack(spacing: 20) {
                VStack(alignment: .leading) {
                    Text("Current State")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(stateMachine.currentState.rawValue)
                        .font(.headline)
                        .foregroundColor(stateColor)
                }

                Divider().frame(height: 30)

                VStack(alignment: .leading) {
                    Text("Bluetooth")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(bleCentral.adapterState.rawValue)
                        .font(.headline)
                }

                Divider().frame(height: 30)

                VStack(alignment: .leading) {
                    Text("Paired Device")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(
                        pairingManager.pairedDevices.isEmpty
                            ? "None"
                            : pairingManager.pairedDevices.map(\.name).joined(separator: ", ")
                    )
                        .font(.headline)
                }

                Spacer()

                Button("Clear Logs") {
                    logger.clear()
                }
            }
            .padding()
            .background(RoundedRectangle(cornerRadius: 8).fill(Color(NSColor.controlBackgroundColor)))

            // Live event logs
            Text("Diagnostic Event Log")
                .font(.subheadline)
                .bold()

            List(logger.events) { event in
                HStack(alignment: .top, spacing: 10) {
                    Text(event.timestamp.formatted(date: .omitted, time: .standard))
                        .font(.system(.caption, design: .monospaced))
                        .foregroundColor(.secondary)

                    Text("[\(event.category)]")
                        .font(.system(.caption, design: .monospaced))
                        .bold()

                    Text(event.message)
                        .font(.caption)

                    Spacer()
                }
                .foregroundColor(severityColor(event.severity))
            }
            .listStyle(.inset)
        }
        .padding()
        .frame(minWidth: 580, maxWidth: .infinity, minHeight: 420, maxHeight: .infinity)
    }

    private var stateColor: Color {
        switch stateMachine.currentState {
        case .authenticatedNear, .unlockCooldown: return .green
        case .connecting, .authenticating, .candidateNear: return .orange
        case .absent: return .gray
        }
    }

    private func severityColor(_ severity: LogSeverity) -> Color {
        switch severity {
        case .info: return .primary
        case .success: return .green
        case .warning: return .orange
        case .error: return .red
        }
    }
}

final class DiagnosticsWindowController: NSWindowController {
    convenience init(stateMachine: PresenceStateMachine, bleCentral: BLECentralManager, pairingManager: PairingManager) {
        let hostingView = NSHostingView(rootView: DiagnosticsView(stateMachine: stateMachine, bleCentral: bleCentral, pairingManager: pairingManager))
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 580, height: 420),
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "BLE Mac Unlock Diagnostics"
        window.contentView = hostingView
        window.center()
        self.init(window: window)
    }
}
