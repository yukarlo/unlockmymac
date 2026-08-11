import Combine
import Foundation
import os

/// Diagnostic event severity.
enum LogSeverity: String, Codable {
    case info = "INFO"
    case success = "SUCCESS"
    case warning = "WARN"
    case error = "ERROR"

    var osLogType: OSLogType {
        switch self {
        case .info, .success: return .info
        case .warning: return .default
        case .error: return .error
        }
    }
}

/// A single diagnostic log entry.
struct LogEvent: Identifiable, Codable {
    let id: UUID
    let timestamp: Date
    let severity: LogSeverity
    let category: String
    let message: String

    init(
        id: UUID = UUID(),
        timestamp: Date = Date(),
        severity: LogSeverity,
        category: String,
        message: String
    ) {
        self.id = id
        self.timestamp = timestamp
        self.severity = severity
        self.category = category
        self.message = message
    }
}

/// Thread-safe in-memory logger for diagnostic events and live UI observation.
///
/// Security: Per Section 2 of the security model, sensitive credentials (macOS passwords,
/// private keys, challenge tokens, and raw ECDSA signatures) are NEVER logged.
final class EventLogger: ObservableObject {

    static let shared = EventLogger()

    @Published private(set) var events: [LogEvent] = []
    private let queue = DispatchQueue(label: "com.karloyu.macbleunlock.eventlogger")
    private let maxEntries = 200
    private let osLog = Logger(subsystem: "com.karloyu.macbleunlock", category: "Event")

    init() {}

    func log(severity: LogSeverity = .info, category: String, message: String) {
        let entry = LogEvent(severity: severity, category: category, message: message)

        // Mirror to the unified log so events survive the app quitting and can be pulled with
        // `log show` after the fact. The in-memory ring is only visible in the Diagnostics
        // window while the app is running, which makes post-hoc debugging — especially of
        // sleep, lock, and auto-unlock behaviour — impossible.
        //
        // Safe by construction: this logger never receives credentials, keys, challenges, or
        // signatures (see the type comment), so `.public` does not expose anything sensitive.
        osLog.log(
            level: severity.osLogType,
            "[\(category, privacy: .public)] \(message, privacy: .public)"
        )

        queue.async { [weak self] in
            guard let self else { return }
            var updated = self.events
            updated.insert(entry, at: 0)
            if updated.count > self.maxEntries {
                updated.removeLast(updated.count - self.maxEntries)
            }
            DispatchQueue.main.async {
                self.events = updated
            }
        }
    }

    func info(category: String, _ message: String) {
        log(severity: .info, category: category, message: message)
    }

    func success(category: String, _ message: String) {
        log(severity: .success, category: category, message: message)
    }

    func warning(category: String, _ message: String) {
        log(severity: .warning, category: category, message: message)
    }

    func error(category: String, _ message: String) {
        log(severity: .error, category: category, message: message)
    }

    func clear() {
        queue.async { [weak self] in
            DispatchQueue.main.async {
                self?.events.removeAll()
            }
        }
    }
}
