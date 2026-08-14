import Foundation

/// Accumulates how long each phase of one unlock attempt took, for a single summary line at the end.
///
/// Answering "why was that slow?" previously meant correlating the Mac's unified log against two
/// devices' event logs by hand, across a clock skew, and reconstructing the phases from state-change
/// lines scattered among hundreds of poll lines. Every constant in this subsystem — the connect
/// watchdog, the backoffs, the RSSI thresholds, the advertising interval — has been retuned from that
/// kind of archaeology, twice from recollection alone. One line per attempt makes "did the fix work?"
/// something you read instead of something you reconstruct.
///
/// Takes its timestamps from the caller rather than reading the clock, so it is testable and so the
/// times line up exactly with the state transitions they describe.
struct UnlockTimeline {

    private struct Mark {
        let state: PresenceState
        let at: Date
    }

    private var marks: [Mark] = []

    var isRunning: Bool { !marks.isEmpty }

    /// Starts a new attempt, discarding anything in progress.
    ///
    /// Discarding rather than merging is deliberate: an attempt that restarts from `.absent` is a new
    /// attempt, and stitching the two together would report a total no user ever waited.
    mutating func begin(_ state: PresenceState, at instant: Date) {
        marks = [Mark(state: state, at: instant)]
    }

    mutating func mark(_ state: PresenceState, at instant: Date) {
        guard isRunning else { return }
        // A repeated state would contribute a zero-length phase and make the line harder to read.
        guard marks.last?.state != state else { return }
        marks.append(Mark(state: state, at: instant))
    }

    mutating func reset() {
        marks = []
    }

    /// One line describing every phase, or `nil` when there is nothing worth reporting.
    ///
    /// Each interval is labelled by the state the attempt was *in* for it, so a slow phase names the
    /// thing that was slow rather than the thing that followed it.
    func summary(endingAt instant: Date) -> String? {
        guard let first = marks.first, marks.count >= 2 else { return nil }

        var phases: [String] = []
        for (index, mark) in marks.enumerated() {
            let next = index + 1 < marks.count ? marks[index + 1].at : instant
            let seconds = next.timeIntervalSince(mark.at)
            // Sub-10ms phases are noise from two transitions landing in the same runloop turn.
            guard seconds >= 0.01 else { continue }
            phases.append(String(format: "%@ %.2fs", mark.state.rawValue, seconds))
        }
        guard !phases.isEmpty else { return nil }

        let total = instant.timeIntervalSince(first.at)
        return String(format: "%@ — total %.2fs", phases.joined(separator: ", "), total)
    }
}
