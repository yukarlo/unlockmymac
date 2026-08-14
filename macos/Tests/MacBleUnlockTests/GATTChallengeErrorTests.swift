import XCTest

@testable import MacBleUnlock

/// How a failure is classified decides the backoff, and the backoff decides whether the next unlock
/// takes a second or two minutes. Two of these classifications were wrong or undocumented until
/// 2026-08-14, so they are pinned here.
final class GATTChallengeErrorTests: XCTestCase {

    func testLinkProblemsAreTransportLevel() {
        let transport: [GATTChallengeError] = [
            .timedOut,
            .connectionFailed(nil),
            .disconnected(nil),
            .serviceDiscoveryFailed(nil),
            .serviceNotFound,
            .characteristicDiscoveryFailed(nil),
            .characteristicNotFound,
            .writeFailed(nil),
            .readFailed(nil),
            .emptyResponse,
        ]
        for error in transport {
            XCTAssertTrue(error.isTransportLevel, "\(error) should take the short retry backoff")
        }
    }

    /// The security-relevant paths must keep the long backoff. `invalidSignature` above all: it is the
    /// path an attacker would exercise, and it must not be retryable in a tight loop.
    func testSecurityRelevantFailuresAreNotTransportLevel() {
        let notTransport: [GATTChallengeError] = [
            .invalidSignature,
            .rejectedByPeer,
            .deniedByUser,
            .missingPairingData,
            .randomBytesUnavailable,
            .sessionAlreadyInProgress,
        ]
        for error in notTransport {
            XCTAssertFalse(error.isTransportLevel, "\(error) must not take the short retry backoff")
        }
    }

    /// Only an explicit "no" counts as a denial. Treating a refusal or a bad signature as one would
    /// apply the two-minute denial backoff to something the user never saw.
    func testOnlyAnExplicitNoIsADenial() {
        XCTAssertTrue(GATTChallengeError.deniedByUser.isDenial)
        XCTAssertFalse(GATTChallengeError.rejectedByPeer.isDenial)
        XCTAssertFalse(GATTChallengeError.invalidSignature.isDenial)
        XCTAssertFalse(GATTChallengeError.timedOut.isDenial)
    }

    /// A rejection is opaque by design, so its description must not leak which check failed — the
    /// Android side returns the same status for every rejection for the same reason.
    func testRejectionDescriptionRevealsNothingSpecific() {
        let text = GATTChallengeError.rejectedByPeer.description.lowercased()
        for leak in ["replay", "clock", "expired", "unpaired", "unknown device", "wrong"] {
            XCTAssertFalse(text.contains(leak), "rejection text leaks '\(leak)'")
        }
    }

    func testEveryCaseHasNonEmptyDescription() {
        let all: [GATTChallengeError] = [
            .sessionAlreadyInProgress, .timedOut, .connectionFailed(nil), .disconnected(nil),
            .serviceDiscoveryFailed(nil), .serviceNotFound, .characteristicDiscoveryFailed(nil),
            .characteristicNotFound, .randomBytesUnavailable, .writeFailed(nil), .readFailed(nil),
            .emptyResponse, .invalidSignature, .missingPairingData, .deniedByUser, .rejectedByPeer,
        ]
        for error in all {
            XCTAssertFalse(error.description.isEmpty, "\(error) has no description")
        }
    }
}
