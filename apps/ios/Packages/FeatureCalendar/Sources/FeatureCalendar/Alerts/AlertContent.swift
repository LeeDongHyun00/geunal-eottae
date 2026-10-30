import Foundation
import GeunalCore

/// What a local notification says (docs/design/tone.md "알림 문구", m1.md decisions 32–34). Every lead word is seen from
/// the moment the alert fires, and dates inside a notification are short: "11/7(토)".
public enum AlertContent {
    public struct Text: Equatable, Sendable {
        public let title: String
        public let body: String

        public init(title: String, body: String) {
            self.title = title
            self.body = body
        }
    }

    /// `end` is the event's end (the last day of an all-day event), when known; `lunarBirthday` is "음력 10월 3일" for a
    /// person whose birthday is lunar.
    public static func text(for alert: ScheduledAlert, end: DayTime? = nil, lunarBirthday: String? = nil) -> Text {
        let fireDay = KoreanCalendar.day(of: alert.fireAt)
        switch alert.target {
        case .event:
            return Text(
                title: alert.title,
                body: alert.allDay
                    ? allDayBody(start: alert.start.day, end: end?.day, fireDay: fireDay)
                    : timedBody(alert, end: end, fireDay: fireDay))
        case .birthday:
            return Text(
                title: String(localized: "\(alert.title) 생일", bundle: .module),
                body: birthdayBody(day: alert.start.day, fireDay: fireDay, lunar: lunarBirthday))
        case .leaveBridge(let bridge):
            let daysOff = Int(bridge.daysOff)
            let leave = compact(bridge.leaveDay)
            let range = compact(bridge.firstDay) + "–" + compact(bridge.lastDay)
            return Text(
                title: String(localized: "연차 1일로 \(daysOff)일 연휴", bundle: .module),
                body: String(localized: "\(leave) 하루 쉬면 \(range) 쉬어요", bundle: .module))
        }
    }

    /// "1시간 뒤 · 18:00–20:00" on the day itself, "내일 · 18:00–20:00" from the day before.
    static func timedBody(_ alert: ScheduledAlert, end: DayTime?, fireDay: String) -> String {
        let lead: String
        if alert.start.day == fireDay, let start = KoreanCalendar.start(of: alert.start.day) {
            let startsAt = start.addingTimeInterval(TimeInterval(alert.start.minute) * 60)
            lead = DayText.until(startsAt, now: alert.fireAt) ?? String(localized: "지금", bundle: .module)
        } else {
            lead = near(alert.start.day, from: fireDay)
        }
        let time =
            end.map { DayText.range(start: alert.start.minute, end: $0.minute) } ?? DayText.clock(alert.start.minute)
        return String(localized: "\(lead) · \(time)", bundle: .module)
    }

    /// "내일 · 종일", "오늘 · 종일", or "내일부터 11/9(월)까지" for days in a row.
    static func allDayBody(start: String, end: String?, fireDay: String) -> String {
        let lead = near(start, from: fireDay)
        if let end, end != start {
            let last = compact(end)
            return String(localized: "\(lead)부터 \(last)까지", bundle: .module)
        }
        return String(localized: "\(lead) · 종일", bundle: .module)
    }

    /// "오늘이에요" on the day, "내일 · 11/7(토)" and "모레 · 11/8(일)" before it, then "D-3 · 11/9(월)"; a lunar birthday
    /// ends with "· 음력 10월 3일".
    static func birthdayBody(day: String, fireDay: String, lunar: String?) -> String {
        let days = KoreanCalendar.days(from: fireDay, to: day) ?? 0
        let body: String
        switch days {
        case ...0:
            body = String(localized: "오늘이에요", bundle: .module)
        case 1, 2:
            let lead = near(day, from: fireDay)
            let date = compact(day)
            body = String(localized: "\(lead) · \(date)", bundle: .module)
        default:
            let date = compact(day)
            body = String(localized: "D-\(days) · \(date)", bundle: .module)
        }
        guard let lunar else { return body }
        return String(localized: "\(body) · \(lunar)", bundle: .module)
    }

    /// 오늘, 내일, 모레, then the short date.
    static func near(_ day: String, from fireDay: String) -> String {
        switch KoreanCalendar.days(from: fireDay, to: day) {
        case 0: String(localized: "오늘", bundle: .module)
        case 1: String(localized: "내일", bundle: .module)
        case 2: String(localized: "모레", bundle: .module)
        default: compact(day)
        }
    }

    /// "11/7(토)".
    static func compact(_ day: String) -> String {
        guard let parsed = KoreanCalendar.parse(day) else { return day }
        let weekday = DayText.weekdayName(KoreanCalendar.weekday(of: day) ?? 1)
        return String(localized: "\(parsed.month)/\(parsed.day)(\(weekday))", bundle: .module)
    }
}
