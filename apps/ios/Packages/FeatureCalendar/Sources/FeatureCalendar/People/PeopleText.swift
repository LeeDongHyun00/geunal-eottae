import GeunalCore

/// How people and birthdays read (m1.md screens 11 and 17): "3월 14일", "음력 1월 3일", "음력 윤4월 3일", and for the
/// next birthday "10월 30일 금요일" or "음력 10월 3일 · 올해는 11월 11일 수요일". No age is ever shown.
public enum PeopleText {
    /// The birthday as entered.
    public static func birthday(_ birthday: Birthday) -> String {
        let month = Int(birthday.month)
        let day = Int(birthday.day)
        switch birthday.calendar {
        case .solar:
            return String(localized: "\(month)월 \(day)일", bundle: .module)
        case .lunar(let leapMonth):
            return leapMonth
                ? String(localized: "음력 윤\(month)월 \(day)일", bundle: .module)
                : String(localized: "음력 \(month)월 \(day)일", bundle: .module)
        }
    }

    /// The upcoming list's line: the solar day the birthday falls on next, after the lunar date when it is one.
    public static func next(_ person: Person, today: String) -> String {
        guard let birthday = person.birthday else { return "" }
        guard let next = person.nextBirthday else { return Self.birthday(birthday) }
        switch birthday.calendar {
        case .solar:
            return DayText.title(next, today: today)
        case .lunar:
            let lunar = Self.birthday(birthday)
            let solar = solarDay(next, today: today)
            return String(localized: "\(lunar) · \(solar)", bundle: .module)
        }
    }

    /// "올해는 11월 11일 수요일", or "내년은 1월 9일 토요일" for a lunar birthday that comes round again next year.
    public static func solarDay(_ day: String, today: String) -> String {
        guard let parsed = KoreanCalendar.parse(day) else { return day }
        let weekday = DayText.weekdayLongName(KoreanCalendar.weekday(of: day) ?? 1)
        if let now = KoreanCalendar.parse(today), now.year != parsed.year {
            return String(localized: "내년은 \(parsed.month)월 \(parsed.day)일 \(weekday)", bundle: .module)
        }
        return String(localized: "올해는 \(parsed.month)월 \(parsed.day)일 \(weekday)", bundle: .module)
    }

    /// "1995년", without the digit grouping a localized integer would get.
    public static func year(_ year: Int) -> String {
        let text = String(year)
        return String(localized: "\(text)년", bundle: .module)
    }

    /// The pill: "D-5", and "오늘" on the day.
    public static func dDay(_ days: UInt16) -> String {
        days == 0 ? String(localized: "오늘", bundle: .module) : "D-\(days)"
    }

    /// Where the birthday came from, under the pill.
    public static func source(_ source: PersonSource) -> String {
        switch source {
        case .manual: String(localized: "직접 등록", bundle: .module)
        case .contacts: String(localized: "연락처에서", bundle: .module)
        case .group: String(localized: "그룹에서 공개", bundle: .module)
        }
    }

    /// The avatar's letter: the first of the name.
    public static func initial(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces).first.map(String.init) ?? ""
    }

    /// Screen 17's line under a lunar birthday.
    public static func lunarNote(solarThisYear day: String?) -> String {
        guard let day, let parsed = KoreanCalendar.parse(day) else {
            return String(localized: "매년 양력으로 바꿔서 넣어요.", bundle: .module)
        }
        let weekday = DayText.weekdayLongName(KoreanCalendar.weekday(of: day) ?? 1)
        return String(
            localized: "매년 양력으로 바꿔서 넣어요. 올해는 \(parsed.month)월 \(parsed.day)일 \(weekday)이에요.", bundle: .module)
    }
}

/// The birthday alert choices (rust-core.md §6 "사람·생일과 음력"): the setting's 당일 09:00, the day before at 18:00, a
/// week before at 09:00, or none. A birthday counts from 00:00 of its day, like an all-day event.
public enum BirthdayAlertText {
    public static let choices: [AlertRule] = [.inherit, .before(minutes: 360), .before(minutes: 9540), .off]

    public static func describe(_ rule: AlertRule) -> String {
        switch rule {
        case .inherit: AlertText.describe(minutes: -540, allDay: true)
        case .off: AlertText.describe(.off, allDay: true)
        case .before(let minutes): AlertText.describe(minutes: minutes, allDay: true)
        }
    }

    /// The menu's line for a choice: the setting's own one says so.
    public static func choice(_ rule: AlertRule) -> String {
        guard rule == .inherit else { return describe(rule) }
        let time = describe(rule)
        return String(localized: "\(time) · 알림 설정을 따름", bundle: .module)
    }
}
