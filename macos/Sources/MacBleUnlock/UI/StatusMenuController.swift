import AppKit
import Combine

/// Builds and updates the NSStatusItem menu reflecting live BLE, state machine, and pairing status.
final class StatusMenuController: NSObject {
    private let statusItem: NSStatusItem
    private let bleCentral: BLECentralManager
    private let pairingManager: PairingManager
    private let stateMachine: PresenceStateMachine
    private let autoUnlockController: AutoUnlockController

    private var pairingWindowController: PairingWindowController?
    private var diagnosticsWindowController: DiagnosticsWindowController?
    private var settingsWindowController: SettingsWindowController?
    private var cancellables = Set<AnyCancellable>()

    init(
        statusItem: NSStatusItem,
        bleCentral: BLECentralManager,
        pairingManager: PairingManager,
        stateMachine: PresenceStateMachine,
        autoUnlockController: AutoUnlockController
    ) {
        self.statusItem = statusItem
        self.bleCentral = bleCentral
        self.pairingManager = pairingManager
        self.stateMachine = stateMachine
        self.autoUnlockController = autoUnlockController
        super.init()
    }

    func install() {
        updateStatusIcon()
        statusItem.menu = buildMenu()

        // Rebuild menu on state changes
        Publishers.CombineLatest4(
            bleCentral.$adapterState,
            stateMachine.$currentState,
            pairingManager.$pairedDevices,
            stateMachine.$isPaused
        )
        .receive(on: DispatchQueue.main)
        .sink { [weak self] _, _, _, _ in
            self?.updateStatusIcon()
            self?.statusItem.menu = self?.buildMenu()
        }
        .store(in: &cancellables)

        bleCentral.$discoveredPeripherals
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.statusItem.menu = self?.buildMenu()
            }
            .store(in: &cancellables)
    }

    private func updateStatusIcon() {
        guard let button = statusItem.button else { return }

        let symbolName: String
        if stateMachine.isPaused {
            symbolName = "pause.circle"
        } else {
            switch stateMachine.currentState {
            case .authenticatedNear, .unlockCooldown:
                symbolName = "lock.open.fill"
            case .connecting, .authenticating, .candidateNear:
                symbolName = "lock.circle"
            case .absent:
                symbolName = "lock.fill"
            }
        }

        button.image = NSImage(
            systemSymbolName: symbolName,
            accessibilityDescription: "MacBleUnlock"
        )
    }

    private func buildMenu() -> NSMenu {
        let menu = NSMenu()

        // Presence state header
        let stateTitle = stateMachine.isPaused ? "Status: PAUSED" : "Status: \(stateMachine.currentState.rawValue)"
        menu.addItem(disabledItem(title: stateTitle))
        menu.addItem(disabledItem(title: adapterStatusTitle))
        menu.addItem(.separator())

        // Paired device & signal info
        if !pairingManager.pairedDevices.isEmpty {
            let names = pairingManager.pairedDevices.map(\.name).joined(separator: ", ")
            menu.addItem(disabledItem(title: "Paired: \(names)"))

            let pairedEntry = bleCentral.discoveredPeripherals.values.first { entry in
                if let authId = stateMachine.authenticatedPeripheralId {
                    return entry.peripheral.identifier == authId
                }
                return true
            }

            if let entry = pairedEntry, let rssi = entry.averageRSSI {
                let rssiText = String(format: "%.0f dBm", rssi)
                let nearSuffix = entry.isNear ? " (near)" : " (far)"
                menu.addItem(disabledItem(title: "  Signal: \(rssiText)\(nearSuffix)"))
            } else {
                menu.addItem(disabledItem(title: "  Signal: Out of range"))
            }
        } else {
            menu.addItem(disabledItem(title: "No phone paired"))
            menu.addItem(.separator())

            let peripherals = bleCentral.discoveredPeripherals.values
                .sorted { $0.lastSeenAt > $1.lastSeenAt }

            if peripherals.isEmpty {
                menu.addItem(disabledItem(title: "Scanning for devices…"))
            } else {
                for entry in peripherals {
                    menu.addItem(disabledItem(title: peripheralTitle(for: entry)))
                }
            }
        }

        menu.addItem(.separator())

        // Toggles & Control items
        let pauseItem = NSMenuItem(
            title: stateMachine.isPaused ? "Resume BLE Unlock" : "Pause BLE Unlock",
            action: #selector(togglePause),
            keyEquivalent: ""
        )
        pauseItem.target = self
        menu.addItem(pauseItem)

        let autoUnlockItem = NSMenuItem(
            title: autoUnlockController.isEnabled ? "Auto-Unlock: Enabled" : "Auto-Unlock: Paused",
            action: #selector(toggleAutoUnlock),
            keyEquivalent: ""
        )
        autoUnlockItem.target = self
        menu.addItem(autoUnlockItem)

        menu.addItem(.separator())

        // Windows
        let pairItem = NSMenuItem(title: "Pair Android Device…", action: #selector(showPairingWindow), keyEquivalent: "p")
        pairItem.target = self
        menu.addItem(pairItem)

        let diagItem = NSMenuItem(title: "Diagnostics…", action: #selector(showDiagnosticsWindow), keyEquivalent: "d")
        diagItem.target = self
        menu.addItem(diagItem)

        let settingsItem = NSMenuItem(title: "Settings…", action: #selector(showSettingsWindow), keyEquivalent: ",")
        settingsItem.target = self
        menu.addItem(settingsItem)

        menu.addItem(.separator())
        menu.addItem(NSMenuItem(
            title: "Quit MacBleUnlock",
            action: #selector(NSApplication.terminate(_:)),
            keyEquivalent: "q"
        ))

        return menu
    }

    @objc private func togglePause() {
        stateMachine.isPaused.toggle()
    }

    @objc private func toggleAutoUnlock() {
        autoUnlockController.isEnabled.toggle()
    }

    @objc private func showPairingWindow() {
        if pairingWindowController == nil {
            pairingWindowController = PairingWindowController(pairingManager: pairingManager, bleCentral: bleCentral)
        }
        pairingWindowController?.showWindow(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func showDiagnosticsWindow() {
        if diagnosticsWindowController == nil {
            diagnosticsWindowController = DiagnosticsWindowController(stateMachine: stateMachine, bleCentral: bleCentral, pairingManager: pairingManager)
        }
        diagnosticsWindowController?.showWindow(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func showSettingsWindow() {
        if settingsWindowController == nil {
            settingsWindowController = SettingsWindowController(stateMachine: stateMachine, autoUnlockController: autoUnlockController)
        }
        settingsWindowController?.showWindow(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    private var adapterStatusTitle: String {
        switch bleCentral.adapterState {
        case .poweredOn:
            if bleCentral.isScanning {
                return "Bluetooth: Scanning"
            }
            // Not scanning while unlocked is the normal resting state now, not a fault —
            // say so, or the menu reads as broken during ordinary use.
            return "Bluetooth: Idle (unlocks when locked)"
        case .poweredOff:
            return "Bluetooth: Off"
        case .unauthorized:
            return "Bluetooth: Access Not Authorized"
        case .unsupported:
            return "Bluetooth: Not Supported"
        case .resetting, .unknown:
            return "Bluetooth: Initializing…"
        }
    }

    private func peripheralTitle(for entry: DiscoveredPeripheral) -> String {
        let rssiText = entry.averageRSSI.map { String(format: "%.0f dBm", $0) } ?? "–"
        let nearSuffix = entry.isNear ? " (near)" : ""
        return "  \(entry.name) — \(rssiText)\(nearSuffix)"
    }

    private func disabledItem(title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }
}
