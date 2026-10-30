import GeunalCore

/// The alert choices and how they read (m1.md screen 9 "알림", rust-core.md §3: all-day events count from 00:00 of
/// their day, so "전날 18:00" is 360 and "당일 09:00" is -540).
public enum AlertText {
    public static func choices(allDay: Bool) -> [AlertRule] {
        if allDay {
            return [.inherit, .off, .before(minutes: -540), .before(minutes: 360)]
        }
        return [
            .inherit, .off, .before(minutes: 0), .before(minutes: 10), .before(minutes: 30), .before(minutes: 60),
            .before(minutes: 1440),
        ]
    }

    public static func describe(_ rule: AlertRule, allDay: Bool) -> String {
        switch rule {
        case .inherit:
            return String(localized: "캘린더 기본", bundle: .module)
        case .off:
            return String(localized: "없음", bundle: .module)
        case .before(let minutes):
            return describe(minutes: minutes, allDay: allDay)
        }
    }

    public static func describe(minutes: Int32, allDay: Bool) -> String {
        if allDay {
            if minutes <= 0 {
                return String(localized: "당일 \(DayText.clock(UInt16(-minutes)))", bundle: .module)
            }
            let minute = (1440 - Int(minutes) % 1440) % 1440
            let days = (Int(minutes) + 1439) / 1440
            return days <= 1
                ? String(localized: "전날 \(DayText.clock(UInt16(minute)))", bundle: .module)
                : String(localized: "\(days)일 전 \(DayText.clock(UInt16(minute)))", bundle: .module)
        }
        switch minutes {
        case 0:
            return String(localized: "정시", bundle: .module)
        case let minutes where minutes % 1440 == 0:
            return String(localized: "\(Int(minutes / 1440))일 전", bundle: .module)
        case let minutes where minutes % 60 == 0:
            return String(localized: "\(Int(minutes / 60))시간 전", bundle: .module)
        default:
            return String(localized: "\(Int(minutes))분 전", bundle: .module)
        }
    }
}
