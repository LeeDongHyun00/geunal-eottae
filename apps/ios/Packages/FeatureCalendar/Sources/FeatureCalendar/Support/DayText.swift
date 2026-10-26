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
