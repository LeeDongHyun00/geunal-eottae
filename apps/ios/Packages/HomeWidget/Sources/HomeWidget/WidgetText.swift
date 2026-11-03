import DesignSystem
import SwiftUI

/// The widgets' words and colors (docs/design/tone.md, boards/Widgets).
public enum WidgetText {
    /// "일요일" for a `"YYYY-MM-DD"` day.
    public static func weekday(_ day: String) -> String {
        switch WidgetDays.weekday(of: day) {
        case 1: String(localized: "일요일", bundle: .module)
        case 2: String(localized: "월요일", bundle: .module)
        case 3: String(localized: "화요일", bundle: .module)
        case 4: String(localized: "수요일", bundle: .module)
        case 5: String(localized: "목요일", bundle: .module)
        case 6: String(localized: "금요일", bundle: .module)
        case 7: String(localized: "토요일", bundle: .module)
        default: ""
        }
    }

    /// "25".
    public static func dayNumber(_ day: String) -> String {
        WidgetDays.parts(of: day).map { String($0.day) } ?? ""
    }

    /// "10월 25일 일요일".
    public static func heading(_ day: String) -> String {
        guard let parts = WidgetDays.parts(of: day) else { return "" }
        let weekday = weekday(day)
        return String(localized: "\(parts.month)월 \(parts.day)일 \(weekday)", bundle: .module)
    }

    /// Red on Sunday, blue on Saturday, ink otherwise, as in the month view's header.
    public static func weekdayColor(_ day: String) -> Color {
        switch WidgetDays.weekday(of: day) {
        case 1: GeunalColor.sunday
        case 7: GeunalColor.saturday
        default: GeunalColor.ink
        }
    }

    /// "18:00–20:00", or "종일".
    public static func time(_ event: WidgetSnapshot.Event) -> String {
        guard !event.allDay, let start = event.start else { return String(localized: "종일", bundle: .module) }
        guard let end = event.end else { return start }
        return start + "–" + end
    }

    /// The calendar's color, or the device calendars' one (m1.md decision 17).
    public static func color(_ event: WidgetSnapshot.Event) -> Color {
        event.color.flatMap(GroupColor.init(rawValue:))?.representative ?? GroupColor.cocoa.tint(2)
    }

    /// The widget gallery's name and line.
    public static var name: String { String(localized: "오늘", bundle: .module) }
    public static var summary: String { String(localized: "오늘과 내일 일정을 보여 줘요", bundle: .module) }
}
