import Foundation

/// Dates and times in the house style (docs/design/tone.md "날짜와 시간"): 오늘·내일·모레, "11월 7일(토)", 24-hour
/// "09:00–18:00" with an en dash, and "3시간 40분 뒤" with two units at most.
public enum DayText {
    public static func weekdayName(_ weekday: Int) -> String {
        switch weekday {
        case 1: String(localized: "일", bundle: .module, comment: "Sunday, short")
        case 2: String(localized: "월", bundle: .module, comment: "Monday, short")
        case 3: String(localized: "화", bundle: .module, comment: "Tuesday, short")
        case 4: String(localized: "수", bundle: .module, comment: "Wednesday, short")
        case 5: String(localized: "목", bundle: .module, comment: "Thursday, short")
        case 6: String(localized: "금", bundle: .module, comment: "Friday, short")
        default: String(localized: "토", bundle: .module, comment: "Saturday, short")
        }
    }

    public static func weekdayLongName(_ weekday: Int) -> String {
        switch weekday {
        case 1: String(localized: "일요일", bundle: .module)
        case 2: String(localized: "월요일", bundle: .module)
        case 3: String(localized: "화요일", bundle: .module)
        case 4: String(localized: "수요일", bundle: .module)
        case 5: String(localized: "목요일", bundle: .module)
        case 6: String(localized: "금요일", bundle: .module)
        default: String(localized: "토요일", bundle: .module)
        }
    }

    /// A day as a title: "10월 30일 금요일", with the year in front in another year than `today`'s.
    public static func title(_ day: String, today: String) -> String {
        guard let parsed = KoreanCalendar.parse(day) else { return day }
        let weekday = weekdayLongName(KoreanCalendar.weekday(of: day) ?? 1)
        if let now = KoreanCalendar.parse(today), now.year != parsed.year {
            let year = String(parsed.year)
            return String(localized: "\(year)년 \(parsed.month)월 \(parsed.day)일 \(weekday)", bundle: .module)
        }
        return String(localized: "\(parsed.month)월 \(parsed.day)일 \(weekday)", bundle: .module)
    }

    /// The list view's day heading: "오늘 · 10월 25일 일요일" for today, the plain title otherwise.
    public static func agendaHeading(_ day: String, today: String) -> String {
        let title = title(day, today: today)
        return day == today ? String(localized: "오늘 · \(title)", bundle: .module) : title
    }

    /// The header's small line and big title for a month: "2026년" and "10월".
    public static func monthHeading(_ month: String) -> (caption: String, title: String) {
        let parts = month.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 2 else { return ("", month) }
        let year = String(parts[0])
        return (
            String(localized: "\(year)년", bundle: .module),
            String(localized: "\(parts[1])월", bundle: .module)
        )
    }

    /// The header for the week from `start` (a Sunday): "2026년 10월" over "25–31일", or "2026년" over
    /// "11월 29일–12월 5일" when the week spans two months.
    public static func weekHeading(start: String) -> (caption: String, title: String) {
        guard let first = KoreanCalendar.parse(start),
            let lastDay = KoreanCalendar.adding(days: 6, to: start),
            let last = KoreanCalendar.parse(lastDay)
        else { return ("", start) }
        let year = String(first.year)
        if first.month == last.month {
            return (
                String(localized: "\(year)년 \(first.month)월", bundle: .module),
                String(localized: "\(first.day)–\(last.day)일", bundle: .module)
            )
        }
        let caption =
            first.year == last.year
            ? String(localized: "\(year)년", bundle: .module)
            : String(localized: "\(year)–\(String(last.year))년", bundle: .module)
        return (
            caption,
            String(localized: "\(first.month)월 \(first.day)일–\(last.month)월 \(last.day)일", bundle: .module)
        )
    }

    /// A suggestion chip's time: hours only when both ends are on the hour ("19–21", "22–02", "22–24"), the clock
    /// otherwise ("09:30–11:00"). An all-day chip has none.
    public static func chipTime(start: UInt16, end: UInt16) -> String {
        if start % 60 == 0 && end % 60 == 0 {
            return String(format: "%02ld–%02ld", Int(start) / 60, Int(end) / 60)
        }
        return clock(start) + "–" + clock(end)
    }

    /// "11월 7일(토)" in the current Korean year, "2027년 1월 3일" in another one.
    public static func short(_ day: String, today: String) -> String {
        guard let parsed = KoreanCalendar.parse(day) else { return day }
        if let now = KoreanCalendar.parse(today), now.year != parsed.year {
            // A string, not an integer: localized integers get digit grouping ("2,027년").
            let year = String(parsed.year)
            return String(localized: "\(year)년 \(parsed.month)월 \(parsed.day)일", bundle: .module)
        }
        let weekday = weekdayName(KoreanCalendar.weekday(of: day) ?? 1)
        return String(localized: "\(parsed.month)월 \(parsed.day)일(\(weekday))", bundle: .module)
    }

    /// 오늘, 내일, 모레, then the short date.
    public static func relativeDay(_ day: String, today: String) -> String {
        switch KoreanCalendar.days(from: today, to: day) {
        case 0: String(localized: "오늘", bundle: .module)
        case 1: String(localized: "내일", bundle: .module)
        case 2: String(localized: "모레", bundle: .module)
        default: short(day, today: today)
        }
    }

    /// "09:00" from minutes after midnight; 1440 (an end at midnight) is "24:00".
    public static func clock(_ minute: UInt16) -> String {
        String(format: "%02ld:%02ld", Int(minute) / 60, Int(minute) % 60)
    }

    /// "09:00–18:00".
    public static func range(start: UInt16, end: UInt16) -> String {
        clock(start) + "–" + clock(end)
    }

    /// "3시간 40분 뒤", "2일 3시간 뒤", "5분 뒤"; `nil` once it has started.
    public static func until(_ start: Date, now: Date) -> String? {
        let minutes = Int((start.timeIntervalSince(now) / 60).rounded(.up))
        guard minutes > 0 else { return nil }
        let days = minutes / 1440
        let hours = (minutes % 1440) / 60
        let rest = minutes % 60
        if days > 0 {
            return hours > 0
                ? String(localized: "\(days)일 \(hours)시간 뒤", bundle: .module)
                : String(localized: "\(days)일 뒤", bundle: .module)
        }
        if hours > 0 {
            return rest > 0
                ? String(localized: "\(hours)시간 \(rest)분 뒤", bundle: .module)
                : String(localized: "\(hours)시간 뒤", bundle: .module)
        }
        return String(localized: "\(rest)분 뒤", bundle: .module)
    }
}
