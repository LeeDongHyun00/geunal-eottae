import Foundation

/// Where a tap on a widget goes (m1.md §2.6): the small widget to the month view on today, an event of the medium
/// widget to its editor. The system hands these straight to the app, which is the only one to read them.
public enum WidgetLink: Equatable, Sendable {
    case today
    case event(eventId: String, occurrence: String?)

    static let scheme = "geunal-widget"

    public var url: URL {
        var parts = URLComponents()
        parts.scheme = Self.scheme
        switch self {
        case .today:
            parts.host = "today"
        case .event(let eventId, let occurrence):
            parts.host = "event"
            parts.queryItems =
                [URLQueryItem(name: "id", value: eventId)]
                + (occurrence.map { [URLQueryItem(name: "occurrence", value: $0)] } ?? [])
        }
        return parts.url ?? URL(fileURLWithPath: "/")
    }

    /// `nil` for any other URL, such as the Kakao sign-in's.
    public init?(url: URL) {
        guard url.scheme == Self.scheme, let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        switch parts.host {
        case "today":
            self = .today
        case "event":
            let items = parts.queryItems ?? []
            guard let eventId = items.first(where: { $0.name == "id" })?.value, !eventId.isEmpty else { return nil }
            self = .event(eventId: eventId, occurrence: items.first { $0.name == "occurrence" }?.value)
        default:
            return nil
        }
    }

    /// The event's link, or `nil` for a snapshot from before the core named its events.
    public static func event(_ event: WidgetSnapshot.Event) -> WidgetLink? {
        event.eventId.map { .event(eventId: $0, occurrence: event.occurrence) }
    }
}

/// Where the snapshot lives in the App Group container: its own directory, which the app keeps out of backups.
public enum WidgetFile {
    public static func directory(in container: URL) -> URL {
        container.appendingPathComponent("widget", isDirectory: true)
    }

    public static func snapshot(in container: URL) -> URL {
        directory(in: container).appendingPathComponent("snapshot.json")
    }
}
