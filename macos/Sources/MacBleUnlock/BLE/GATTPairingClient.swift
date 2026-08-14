import CoreBluetooth
import Foundation
import os

/// Handles automated zero-touch BLE pairing over characteristic `...768`.
final class GATTPairingClient: NSObject {

    typealias Completion = (Result<PairedDevice, Error>) -> Void

    private let bleCentral: BLECentralManager
    private let pairingManager: PairingManager
    private let log = Logger(subsystem: "com.karloyu.macbleunlock", category: "GATTPairingClient")

    private var activePeripheral: CBPeripheral?
    private var activeToken: String?
    private var pairingCharacteristic: CBCharacteristic?
    private var completion: Completion?
    private var timeoutWorkItem: DispatchWorkItem?

    init(bleCentral: BLECentralManager, pairingManager: PairingManager) {
        self.bleCentral = bleCentral
        self.pairingManager = pairingManager
        super.init()
    }

    /// Attempts to pair with `peripheral` using `token`.
    func pair(peripheral: CBPeripheral, token: String, completion: @escaping Completion) {
        bleCentral.queue.async { [weak self] in
            guard let self else { return }

            guard self.activePeripheral == nil else {
                DispatchQueue.main.async {
                    completion(.failure(NSError(domain: "GATTPairingClient", code: -9, userInfo: [NSLocalizedDescriptionKey: "Pairing session already in progress"])))
                }
                return
            }

            self.activePeripheral = peripheral
            self.activeToken = token
            self.completion = completion
            peripheral.delegate = self
            self.bleCentral.connectionDelegate = self

            self.scheduleTimeout()
            self.log.notice("Connecting to \(peripheral.identifier.uuidString, privacy: .public) for GATT pairing exchange")
            EventLogger.shared.info(category: "Pairing", "Connecting over BLE to complete pairing...")
            self.bleCentral.connect(peripheral)
        }
    }

    /// Abandons an in-flight exchange, releasing the link and the shared connection delegate.
    ///
    /// Needed when the pairing window closes mid-exchange. Without it the peripheral stayed connected
    /// and `bleCentral.connectionDelegate` stayed pointed here until the 15s timeout below, so a
    /// screen lock in that window found `GATTChallengeClient` unable to start at all.
    ///
    /// Silent: the caller walked away, so there is no result anyone is waiting for.
    func cancel() {
        bleCentral.queue.async { [weak self] in
            guard let self, let peripheral = self.activePeripheral else { return }
            self.log.notice("Pairing exchange cancelled; releasing the link")
            self.finish(
                .failure(NSError(
                    domain: "GATTPairingClient",
                    code: -10,
                    userInfo: [NSLocalizedDescriptionKey: "Pairing cancelled"]
                )),
                for: peripheral,
                disconnect: true,
                invokeCompletion: false
            )
        }
    }

    private func scheduleTimeout() {
        timeoutWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, let peripheral = self.activePeripheral else { return }
            self.log.warning("GATT pairing exchange timed out")
            self.finish(.failure(NSError(domain: "GATTPairingClient", code: -1, userInfo: [NSLocalizedDescriptionKey: "Pairing timed out"])), for: peripheral, disconnect: true)
        }
        timeoutWorkItem = workItem
        bleCentral.queue.asyncAfter(deadline: .now() + 15, execute: workItem)
    }

    private func finish(
        _ result: Result<PairedDevice, Error>,
        for peripheral: CBPeripheral,
        disconnect: Bool,
        invokeCompletion: Bool = true
    ) {
        guard activePeripheral === peripheral else { return }

        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil

        let pendingCompletion = completion
        activePeripheral = nil
        activeToken = nil
        pairingCharacteristic = nil
        completion = nil
        peripheral.delegate = nil

        if bleCentral.connectionDelegate === self {
            bleCentral.connectionDelegate = nil
        }

        if disconnect {
            bleCentral.cancelConnection(peripheral)
        }

        if invokeCompletion {
            DispatchQueue.main.async {
                pendingCompletion?(result)
            }
        }
    }
}

extension GATTPairingClient: BLEPeripheralConnectionDelegate {

    func bleCentral(_ manager: BLECentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === activePeripheral else { return }
        log.notice("Connected for pairing, discovering unlock service")
        peripheral.discoverServices([BLEProtocol.serviceUUID])
    }

    func bleCentral(_ manager: BLECentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === activePeripheral else { return }
        finish(.failure(error ?? NSError(domain: "GATTPairingClient", code: -2)), for: peripheral, disconnect: false)
    }

    func bleCentralDidInvalidateConnections(_ manager: BLECentralManager) {
        guard let peripheral = activePeripheral else { return }
        log.notice("Radio invalidated all connections; abandoning pairing exchange")
        finish(
            .failure(NSError(domain: "GATTPairingClient", code: -10, userInfo: [
                NSLocalizedDescriptionKey: "Bluetooth became unavailable during pairing",
            ])),
            for: peripheral,
            disconnect: false
        )
    }

    func bleCentral(_ manager: BLECentralManager, didDisconnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === activePeripheral else { return }
        finish(.failure(error ?? NSError(domain: "GATTPairingClient", code: -3)), for: peripheral, disconnect: false)
    }
}

extension GATTPairingClient: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === activePeripheral else { return }
        if let error {
            finish(.failure(error), for: peripheral, disconnect: true)
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == BLEProtocol.serviceUUID }) else {
            finish(.failure(NSError(domain: "GATTPairingClient", code: -4, userInfo: [NSLocalizedDescriptionKey: "Service not found"])), for: peripheral, disconnect: true)
            return
        }
        peripheral.discoverCharacteristics([BLEProtocol.pairingCharacteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === activePeripheral else { return }
        if let error {
            finish(.failure(error), for: peripheral, disconnect: true)
            return
        }
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == BLEProtocol.pairingCharacteristicUUID }),
              let token = activeToken else {
            finish(.failure(NSError(domain: "GATTPairingClient", code: -5, userInfo: [NSLocalizedDescriptionKey: "Pairing characteristic or active token missing"])), for: peripheral, disconnect: true)
            return
        }

        let claimPayload = """
        mac-ble-pair:v1
        macInstallationId=\(pairingManager.macInstallationId)
        token=\(token)
        issuedAt=\(Int64(Date().timeIntervalSince1970 * 1000))
        """

        self.pairingCharacteristic = characteristic
        log.notice("Writing pairing claim payload")
        peripheral.writeValue(Data(claimPayload.utf8), for: characteristic, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral, characteristic.uuid == BLEProtocol.pairingCharacteristicUUID else { return }
        if let error {
            finish(.failure(error), for: peripheral, disconnect: true)
            return
        }
        log.notice("Pairing claim written, reading phone identity response")
        peripheral.readValue(for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === activePeripheral, characteristic.uuid == BLEProtocol.pairingCharacteristicUUID else { return }
        if let error {
            finish(.failure(error), for: peripheral, disconnect: true)
            return
        }
        guard let responseData = characteristic.value, let responseText = String(data: responseData, encoding: .utf8) else {
            finish(.failure(NSError(domain: "GATTPairingClient", code: -6, userInfo: [NSLocalizedDescriptionKey: "Empty pairing response"])), for: peripheral, disconnect: true)
            return
        }

        // Parse response lines
        let lines = responseText.split(separator: "\n").map(String.init)
        guard lines.first == "mac-ble-pair-resp:v1" else {
            finish(.failure(NSError(domain: "GATTPairingClient", code: -7, userInfo: [NSLocalizedDescriptionKey: "Invalid response prefix"])), for: peripheral, disconnect: true)
            return
        }

        var deviceId: String?
        var name: String?
        var publicKeyBase64: String?

        for line in lines.dropFirst() {
            let parts = line.split(separator: "=", maxSplits: 1).map(String.init)
            if parts.count == 2 {
                switch parts[0] {
                case "deviceId": deviceId = parts[1]
                case "name": name = parts[1]
                case "publicKey": publicKeyBase64 = parts[1]
                default: break
                }
            }
        }

        guard let deviceId, let name, let publicKeyBase64, let derData = Data(base64Encoded: publicKeyBase64) else {
            finish(.failure(NSError(domain: "GATTPairingClient", code: -8, userInfo: [NSLocalizedDescriptionKey: "Malformed pairing identity data"])), for: peripheral, disconnect: true)
            return
        }

        log.notice("Successfully received identity from phone '\(name)' (\(deviceId))")
        pairingManager.pair(deviceId: deviceId, name: name, publicKeyDER: derData)

        let pairedRecord = PairedDevice(deviceId: deviceId, name: name, publicKeyDER: derData, pairedAt: Date())
        finish(.success(pairedRecord), for: peripheral, disconnect: true)
    }
}
