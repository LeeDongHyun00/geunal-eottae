import Foundation

/// Every calendar day and time the core hands over is Korean time (docs/parts/rust-core.md §3 "날짜와 시각"), so the
/// screens compute "today" and weekdays in Asia/Seoul whatever the phone's own time zone is.
public enum KoreanCalendar {
    public static let timeZone = TimeZone(identifier: "Asia/Seoul") ?? TimeZone(secondsFromGMT: 9 * 3600)!

    public static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.locale = Locale(identifier: "ko_KR")
        return calendar
    }

    /// `"YYYY-MM-DD"` of the Korean day `date` falls on.
    public static func day(of date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04ld-%02ld-%02ld", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    public struct Day: Equatable, Sendable {
        public let year: Int
        public let month: Int
        public let day: Int
    }

    public static func parse(_ day: String) -> Day? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return Day(year: parts[0], month: parts[1], day: parts[2])
    }

    /// Midnight of the Korean day.
    public static func start(of day: String) -> Date? {
        guard let parsed = parse(day) else { return nil }
        return calendar.date(from: DateComponents(year: parsed.year, month: parsed.month, day: parsed.day))
    }

    /// Whole days from `from` to `to`; negative when `to` is earlier.
    public static func days(from: String, to: String) -> Int? {
        guard let first = start(of: from), let last = start(of: to) else { return nil }
        return calendar.dateComponents([.day], from: first, to: last).day
    }

    /// 1 for Sunday through 7 for Saturday.
    public static func weekday(of day: String) -> Int? {
        start(of: day).map { calendar.component(.weekday, from: $0) }
    }

    /// The day `count` days after `day`; a negative count goes back.
    public static func adding(days count: Int, to day: String) -> String? {
        guard let first = start(of: day), let date = calendar.date(byAdding: .day, value: count, to: first) else {
            return nil
        }
        return self.day(of: date)
    }

    /// `"YYYY-MM"` of a day.
    public static func month(of day: String) -> String {
        String(day.prefix(7))
    }

    /// The month `count` months after `month` (`"YYYY-MM"`); a negative count goes back.
    public static func adding(months count: Int, to month: String) -> String? {
        let parts = month.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 2, (1...12).contains(parts[1]) else { return nil }
        let index = parts[0] * 12 + parts[1] - 1 + count
        return String(format: "%04ld-%02ld", index / 12, index % 12 + 1)
    }

    /// The Sunday that starts the week `day` falls in; weeks run Sunday to Saturday like the month grid.
    public static func weekStart(of day: String) -> String? {
        weekday(of: day).flatMap { adding(days: 1 - $0, to: day) }
    }
}
