import GeunalCore

/// How a repeat rule reads in the list view and the quick entry's repeat chip: "매일", "매주 금요일", "매주 월·수",
/// "2주마다 월요일", "매월 15일", "매년" (docs/design/m1.md screen 8).
public enum RecurrenceText {
    /// `startDay` gives the weekday or day of month a rule without its own days falls on.
    public static func describe(_ recurrence: Recurrence, startDay: String) -> String {
        let interval = Int(recurrence.interval)
        switch recurrence.frequency {
        case .daily:
            return interval <= 1
                ? String(localized: "매일", bundle: .module)
                : String(localized: "\(interval)일마다", bundle: .module)
        case .weekly:
            let days = weekdays(recurrence, startDay: startDay)
            return interval <= 1
                ? String(localized: "매주 \(days)", bundle: .module)
                : String(localized: "\(interval)주마다 \(days)", bundle: .module)
        case .monthly:
            let day = KoreanCalendar.parse(startDay)?.day ?? 1
            return interval <= 1
                ? String(localized: "매월 \(day)일", bundle: .module)
                : String(localized: "\(interval)개월마다 \(day)일", bundle: .module)
        case .yearly:
            return interval <= 1
                ? String(localized: "매년", bundle: .module)
                : String(localized: "\(interval)년마다", bundle: .module)
        }
    }

    /// One day reads in full ("금요일"); several are short and joined by a middle dot ("월·수").
    static func weekdays(_ recurrence: Recurrence, startDay: String) -> String {
        let numbers =
            recurrence.weekdays.isEmpty
            ? [KoreanCalendar.weekday(of: startDay) ?? 1]
            : recurrence.weekdays.map(\.number).sorted()
        if numbers.count == 1 {
            return DayText.weekdayLongName(numbers[0])
        }
        return numbers.map(DayText.weekdayName).joined(separator: "·")
    }
}
