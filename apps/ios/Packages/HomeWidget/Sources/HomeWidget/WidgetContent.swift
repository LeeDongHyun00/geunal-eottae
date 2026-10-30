import Foundation

/// What a widget shows at a moment (docs/design/m1.md screen 14): the Korean day with its events, or why there are
/// none to show.
public struct WidgetContent: Equatable, Sendable {
    public enum Source: Equatable, Sendable {
        /// No snapshot yet: before the first account, or after a sign-out or a deletion took it away.
        case missing
        /// Another version, or a broken file.
        case unreadable
        case snapshot(WidgetSnapshot)
    }

    public enum State: Equatable, Sendable {
        /// "그날어때를 열어 시작하세요".
        case start
        /// "그날어때를 열어 주세요": a snapshot this widget cannot read, or one too old to say anything about the day.
        case openApp
        /// The day's events; `tomorrow` is `nil` once midnight passed before the app wrote the next snapshot.
        case events(today: [WidgetSnapshot.Event], tomorrow: [WidgetSnapshot.Event]?)
    }

    /// The Korean day on screen, `"YYYY-MM-DD"`.
    public let day: String
    public let state: State

    public init(day: String, state: State) {
        self.day = day
        self.state = state
    }

    /// The content for the Korean day `day`. A snapshot of the day before shows its tomorrow as today, as the app may
    /// not run between midnight and the first look (rust-core.md §3).
    public static func make(_ source: Source, day: String) -> WidgetContent {
        switch source {
        case .missing:
            return WidgetContent(day: day, state: .start)
        case .unreadable:
            return WidgetContent(day: day, state: .openApp)
        case .snapshot(let snapshot):
            if snapshot.day == day {
                return WidgetContent(day: day, state: .events(today: snapshot.today, tomorrow: snapshot.tomorrow))
            }
            if WidgetDays.adding(1, to: snapshot.day) == day {
                return WidgetContent(day: day, state: .events(today: snapshot.tomorrow, tomorrow: nil))
            }
            return WidgetContent(day: day, state: .openApp)
        }
    }
}

/// Korean days for the widget, which does not link the calendar package: the dates the core hands over are Korean
/// time (rust-core.md §3 "날짜와 시각").
public enum WidgetDays {
    public static let timeZone = TimeZone(identifier: "Asia/Seoul") ?? TimeZone(secondsFromGMT: 9 * 3600)!

    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }

    public static func day(of date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04ld-%02ld-%02ld", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// The next Korean midnight after `date`, when a timeline turns the page.
    public static func nextMidnight(after date: Date) -> Date {
        let start = calendar.startOfDay(for: date)
        return calendar.date(byAdding: .day, value: 1, to: start) ?? date.addingTimeInterval(86_400)
    }

    static func date(of day: String) -> Date? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    static func adding(_ days: Int, to day: String) -> String? {
        guard let date = date(of: day), let next = calendar.date(byAdding: .day, value: days, to: date) else {
            return nil
        }
        return Self.day(of: next)
    }

    /// 1 is Sunday, 7 is Saturday.
    public static func weekday(of day: String) -> Int? {
        date(of: day).map { calendar.component(.weekday, from: $0) }
    }

    public static func parts(of day: String) -> (month: Int, day: Int)? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return (parts[1], parts[2])
    }
}
