import Foundation
import Combine

/// Diagnostic event severity.
enum LogSeverity: String, Codable {
    case info = "INFO"
    case success = "SUCCESS"
    case warning = "WARN"
    case error = "ERROR"
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

    init() {}

    func log(severity: LogSeverity = .info, category: String, message: String) {
        let entry = LogEvent(severity: severity, category: category, message: message)
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
