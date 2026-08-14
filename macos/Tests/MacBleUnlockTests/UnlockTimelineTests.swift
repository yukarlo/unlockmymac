import XCTest

@testable import MacBleUnlock

final class UnlockTimelineTests: XCTestCase {

    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    private func at(_ offset: TimeInterval) -> Date { t0.addingTimeInterval(offset) }

    func testNothingToReportBeforeAnyPhaseCompletes() {
        var timeline = UnlockTimeline()
        XCTAssertNil(timeline.summary(endingAt: at(1)))

        timeline.begin(.candidateNear, at: t0)
        // One mark is a start, not a phase.
        XCTAssertNil(timeline.summary(endingAt: at(1)))
    }

    /// The line this whole type exists to produce.
    func testReportsEachPhaseAndTheTotal() {
        var timeline = UnlockTimeline()
        timeline.begin(.candidateNear, at: t0)
        timeline.mark(.connecting, at: at(0.5))
        timeline.mark(.authenticating, at: at(1.2))
        timeline.mark(.authenticatedNear, at: at(9.8))

        let summary = timeline.summary(endingAt: at(9.9))

        XCTAssertNotNil(summary)
        let text = summary ?? ""
        XCTAssertTrue(text.contains("Device Nearby 0.50s"), text)
        XCTAssertTrue(text.contains("Connecting to Device… 0.70s"), text)
        XCTAssertTrue(text.contains("Authenticating… 8.60s"), text)
        XCTAssertTrue(text.contains("total 9.90s"), text)
    }

    /// Each interval must be named for the state it was *in*, not the one it moved to — otherwise a
    /// slow phase points at the wrong culprit, which is the mistake the line is meant to prevent.
    func testPhaseIsLabelledByTheStateItWasIn() {
        var timeline = UnlockTimeline()
        timeline.begin(.candidateNear, at: t0)
        timeline.mark(.connecting, at: at(0.1))
        timeline.mark(.authenticating, at: at(5.0))

        let text = timeline.summary(endingAt: at(5.1)) ?? ""
        // The 4.9s was spent connecting, so "Connecting" carries it.
        XCTAssertTrue(text.contains("Connecting to Device… 4.90s"), text)
    }

    func testSubTenMillisecondPhasesAreOmittedAsNoise() {
        var timeline = UnlockTimeline()
        timeline.begin(.candidateNear, at: t0)
        // Two transitions landing in the same runloop turn.
        timeline.mark(.connecting, at: at(0.001))
        timeline.mark(.authenticating, at: at(0.002))
        timeline.mark(.authenticatedNear, at: at(3.0))

        let text = timeline.summary(endingAt: at(3.01)) ?? ""
        XCTAssertFalse(text.contains("Device Nearby"), "a 1ms phase should not be listed: \(text)")
        // 3.000 - 0.002 = 2.998, which rounds to 3.00 at two decimals.
        XCTAssertTrue(text.contains("Authenticating… 3.00s"), text)
        // The total still covers the whole attempt, including the phases too short to list.
        XCTAssertTrue(text.contains("total 3.01s"), text)
    }

    func testRepeatedStateDoesNotCreateAnEmptyPhase() {
        var timeline = UnlockTimeline()
        timeline.begin(.candidateNear, at: t0)
        timeline.mark(.connecting, at: at(1))
        timeline.mark(.connecting, at: at(2))

        let text = timeline.summary(endingAt: at(3)) ?? ""
        XCTAssertEqual(text.components(separatedBy: "Connecting").count - 1, 1, text)
    }

    /// A retry is a new attempt. Stitching it onto the previous one would report a total nobody waited.
    func testBeginDiscardsAnAttemptInProgress() {
        var timeline = UnlockTimeline()
        timeline.begin(.candidateNear, at: t0)
        timeline.mark(.connecting, at: at(5))

        timeline.begin(.candidateNear, at: at(60))
        timeline.mark(.connecting, at: at(60.5))

        let text = timeline.summary(endingAt: at(61)) ?? ""
        XCTAssertTrue(text.contains("total 1.00s"), "expected only the second attempt: \(text)")
    }

    func testMarkBeforeBeginIsIgnored() {
        var timeline = UnlockTimeline()
        timeline.mark(.connecting, at: t0)
        XCTAssertFalse(timeline.isRunning)
        XCTAssertNil(timeline.summary(endingAt: at(1)))
    }

    func testResetClearsEverything() {
        var timeline = UnlockTimeline()
        timeline.begin(.candidateNear, at: t0)
        timeline.mark(.connecting, at: at(1))
        timeline.reset()

        XCTAssertFalse(timeline.isRunning)
        XCTAssertNil(timeline.summary(endingAt: at(2)))
    }
}
