import AppKit

/// Owns app-wide singletons, status item menu, and long-lived BLE state machine.
final class AppDelegate: NSObject, NSApplicationDelegate {

    private var statusItem: NSStatusItem!
    private var menuController: StatusMenuController!

    let bleCentral = BLECentralManager()
    let pairingManager = PairingManager()
    let systemActionController = SystemActionController()
    let autoUnlockController = AutoUnlockController()
    lazy var gattChallengeClient = GATTChallengeClient(bleCentral: bleCentral, pairingManager: pairingManager)
    lazy var presenceStateMachine = PresenceStateMachine(
        bleCentral: bleCentral,
        gattClient: gattChallengeClient,
        pairingManager: pairingManager,
        systemActionController: systemActionController,
        autoUnlockController: autoUnlockController
    )

    func applicationDidFinishLaunching(_ notification: Notification) {
        autoUnlockController.systemActionController = systemActionController
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        menuController = StatusMenuController(
            statusItem: statusItem,
            bleCentral: bleCentral,
            pairingManager: pairingManager,
            stateMachine: presenceStateMachine,
            autoUnlockController: autoUnlockController
        )
        menuController.install()

        // Eagerly initialize state machine listeners and start BLE scanning
        _ = presenceStateMachine
        bleCentral.start()

        EventLogger.shared.info(category: "App", "MacBleUnlock started (Installation ID: \(pairingManager.macInstallationId))")
    }

    func applicationWillTerminate(_ notification: Notification) {
        gattChallengeClient.cancel()
        bleCentral.stop()
        EventLogger.shared.info(category: "App", "MacBleUnlock shutting down")
    }

    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
        true
    }
}
