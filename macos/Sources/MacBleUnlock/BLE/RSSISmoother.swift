import Foundation

/// A simple rolling-window smoother for BLE RSSI samples (plan section 8).
///
/// Keeps only the most recent `windowSize` samples so a single noisy or missed
/// advertisement can't flip a proximity decision on its own. RSSI is only ever a
/// *hint* used to decide when to attempt the cryptographic GATT handshake — it is
/// never sufficient by itself to authenticate or unlock (plan section 2).
struct RSSISmoother: Equatable {
    private(set) var samples: [Int] = []
    let windowSize: Int

    init(windowSize: Int = BLEProtocol.rssiSampleWindow) {
        self.windowSize = max(1, windowSize)
    }

    mutating func addSample(_ rssi: Int) {
        samples.append(rssi)
        if samples.count > windowSize {
            samples.removeFirst(samples.count - windowSize)
        }
    }

    mutating func reset() {
        samples.removeAll()
    }

    /// Average RSSI across the current window, or `nil` if no samples have arrived yet.
    var average: Double? {
        guard !samples.isEmpty else { return nil }
        return Double(samples.reduce(0, +)) / Double(samples.count)
    }

    /// Whether we've collected enough samples to trust `average` for a state transition.
    var hasFullWindow: Bool {
        samples.count >= windowSize
    }

    var isNear: Bool {
        guard let average else { return false }
        return average >= Double(BLEProtocol.nearRSSIThresholdDBm)
    }

    var isFar: Bool {
        guard let average else { return true }
        return average <= Double(BLEProtocol.farRSSIThresholdDBm)
    }
}
