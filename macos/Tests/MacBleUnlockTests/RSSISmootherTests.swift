import XCTest

@testable import MacBleUnlock

/// The smoother exists so one noisy or missed advertisement cannot move a presence decision. These
/// pin that down, because the thresholds around it have been retuned several times and the smoothing
/// underneath was never verified.
final class RSSISmootherTests: XCTestCase {

    func testNoAverageBeforeAnySample() {
        let smoother = RSSISmoother()
        XCTAssertNil(smoother.average)
        XCTAssertFalse(smoother.hasFullWindow)
    }

    func testAveragesTheSamplesItHas() {
        var smoother = RSSISmoother(windowSize: 4)
        [-60, -70].forEach { smoother.addSample($0) }
        XCTAssertEqual(smoother.average ?? 0, -65, accuracy: 0.001)
    }

    func testKeepsOnlyTheMostRecentWindow() {
        var smoother = RSSISmoother(windowSize: 3)
        // The first two must fall out entirely, not merely be diluted.
        [-100, -100, -50, -50, -50].forEach { smoother.addSample($0) }
        XCTAssertEqual(smoother.average ?? 0, -50, accuracy: 0.001)
    }

    func testHasFullWindowOnlyOnceTheWindowIsFull() {
        var smoother = RSSISmoother(windowSize: 3)
        smoother.addSample(-60)
        XCTAssertFalse(smoother.hasFullWindow)
        smoother.addSample(-60)
        XCTAssertFalse(smoother.hasFullWindow)
        smoother.addSample(-60)
        XCTAssertTrue(smoother.hasFullWindow)
    }

    /// A single outlier must not drag the average across a threshold on its own — that is the whole
    /// reason presence is not decided from one advertisement.
    func testOneOutlierDoesNotDominate() {
        var smoother = RSSISmoother(windowSize: 5)
        [-60, -60, -60, -60].forEach { smoother.addSample($0) }
        let before = smoother.average ?? 0
        smoother.addSample(-95)
        let after = smoother.average ?? 0
        XCTAssertLessThan(after, before)
        XCTAssertGreaterThan(after, -70, "one bad sample in five moved the average more than 10 dBm")
    }

    func testWindowSizeIsNeverZero() {
        // A zero or negative window would divide by nothing on the first sample.
        var smoother = RSSISmoother(windowSize: 0)
        smoother.addSample(-60)
        XCTAssertEqual(smoother.average ?? 0, -60, accuracy: 0.001)
    }
}
