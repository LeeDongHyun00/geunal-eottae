import Foundation

/// The JSON the app writes for the widgets (docs/parts/rust-core.md §3 "위젯 스냅샷"). The core makes it; the widget
/// only reads it, so a newer app may add keys the widget ignores.
public struct WidgetSnapshot: Decodable, Equatable, Sendable {
    /// The format this widget reads; another version waits for the app.
    public static let version = 1
    /// In the App Group container, next to the core's store directory.
    public static let fileName = "widget.json"

    public let v: Int
    /// The Korean day the app wrote it for.
    public let day: String
    public let today: [Event]
    public let tomorrow: [Event]

    public init(v: Int = WidgetSnapshot.version, day: String, today: [Event], tomorrow: [Event]) {
        self.v = v
        self.day = day
        self.today = today
        self.tomorrow = tomorrow
    }

    public struct Event: Decodable, Equatable, Sendable {
        /// What `event(eventId, occurrence)` opens; snapshots from before core #55 have neither.
        public let eventId: String?
        public let occurrence: String?
        public let title: String
        /// A key of tokens.json `color.group`, or `nil` for a device calendar's event.
        public let color: String?
        public let allDay: Bool
        /// `"HH:MM"`, or `nil` for an all-day event.
        public let start: String?
        public let end: String?

        public init(
            eventId: String? = nil, occurrence: String? = nil, title: String, color: String?, allDay: Bool,
            start: String?, end: String?
        ) {
            self.eventId = eventId
            self.occurrence = occurrence
            self.title = title
            self.color = color
            self.allDay = allDay
            self.start = start
            self.end = end
        }

        enum CodingKeys: String, CodingKey {
            case eventId = "event_id"
            case occurrence
            case title
            case color
            case allDay = "all_day"
            case start
            case end
        }
    }

    enum CodingKeys: String, CodingKey {
        case v
        case day
        case today
        case tomorrow
    }

    /// Reads the version first, so a snapshot of another shape still says what it is.
    public static func read(_ data: Data?) -> WidgetContent.Source {
        guard let data else { return .missing }
        struct Version: Decodable { let v: Int }
        let decoder = JSONDecoder()
        guard let version = try? decoder.decode(Version.self, from: data), version.v == Self.version,
            let snapshot = try? decoder.decode(WidgetSnapshot.self, from: data)
        else { return .unreadable }
        return .snapshot(snapshot)
    }
}
