#if DEBUG
    import FeatureCalendar
    import Foundation
    import GeunalCore

    /// The design's example week (docs/design/screens/m1/04-month) written into the real core, debug builds only, for
    /// the calendar UI tests and their screenshots. Everything is placed relative to today so the pictures look the
    /// same whenever CI runs.
    enum DemoCalendar {
        static func seed(_ core: any CoreProtocol, now: Date = Date()) throws {
            let today = KoreanCalendar.day(of: now)
            guard let sunday = KoreanCalendar.weekStart(of: today),
                let start = KoreanCalendar.adding(days: -28, to: sunday)
            else { return }
            let personal = try core.calendars().first { $0.kind == .own }?.id
            let school = try calendar(core, "학교", .sea)
            let work = try calendar(core, "알바", .mint)
            let gym = try calendar(core, "운동", .olive, soft: true)
            let family = try calendar(core, "가족", .blossom)

            func day(_ offset: Int, from base: String) -> String {
                KoreanCalendar.adding(days: offset, to: base) ?? base
            }
            try add(core, school, "전공 수업", day(1, from: start), 600, 720, weekly: [.monday, .wednesday])
            try add(core, school, "교양 수업", day(2, from: start), 780, 900, weekly: [.tuesday, .thursday])
            try add(core, gym, "헬스", day(1, from: start), 420, 480, weekly: [.monday, .wednesday, .friday])
            try add(core, work, "카페 오픈", day(6, from: start), 480, 780, weekly: [.saturday])
            // Two of today's weekday typed one by one: the third, from the quick entry, makes the core offer the weekly
            // repeat (m1.md screen 6).
            try add(core, work, "카페 마감", day(-14, from: today), 1020, 1320)
            try add(core, work, "카페 마감", day(-7, from: today), 1020, 1320)
            try add(core, family, "가족 · 저녁", day(1, from: today), 1080, 1200)
            if let personal {
                try add(core, personal, "치과", day(4, from: today), 900, 960)
            }
            if let birthday = KoreanCalendar.parse(day(4, from: today)) {
                _ = try core.savePerson(
                    input: PersonInput(
                        id: nil, name: "민수",
                        birthday: Birthday(
                            month: UInt8(birthday.month), day: UInt8(birthday.day), year: nil, calendar: .solar),
                        alert: .inherit, suggest: true))
            }
        }

        /// The people of docs/design/screens/m1/11-people: three birthdays in the next weeks, one of them lunar, and
        /// four further off.
        static func seedPeople(_ core: any CoreProtocol, now: Date = Date()) throws {
            let today = KoreanCalendar.day(of: now)
            func solar(_ offset: Int) -> Birthday? {
                guard let day = KoreanCalendar.adding(days: offset, to: today).flatMap(KoreanCalendar.parse) else {
                    return nil
                }
                return Birthday(month: UInt8(day.month), day: UInt8(day.day), year: nil, calendar: .solar)
            }
            // The lunar date of a solar day, from the system's Chinese calendar, whose month and day numbers the
            // core's Korean table shares nearly always: close enough for a picture.
            func lunar(_ offset: Int) -> Birthday? {
                guard let day = KoreanCalendar.adding(days: offset, to: today), let date = KoreanCalendar.start(of: day)
                else { return nil }
                var chinese = Calendar(identifier: .chinese)
                chinese.timeZone = KoreanCalendar.timeZone
                let parts = chinese.dateComponents([.month, .day], from: date)
                guard let month = parts.month, let dayOfMonth = parts.day else { return nil }
                return Birthday(
                    month: UInt8(month), day: UInt8(dayOfMonth), year: nil,
                    calendar: .lunar(leapMonth: parts.isLeapMonth == true))
            }
            let people: [(String, Birthday?)] = [
                ("민수", solar(5)),
                ("엄마", lunar(17)),
                ("서연", solar(25)),
                ("가영", Birthday(month: 3, day: 14, year: nil, calendar: .solar)),
                ("도윤", Birthday(month: 6, day: 2, year: 1998, calendar: .solar)),
                ("아빠", Birthday(month: 1, day: 3, year: nil, calendar: .lunar(leapMonth: false))),
                ("할머니", Birthday(month: 8, day: 20, year: nil, calendar: .lunar(leapMonth: false))),
            ]
            for (name, birthday) in people {
                _ = try core.savePerson(
                    input: PersonInput(id: nil, name: name, birthday: birthday, alert: .inherit, suggest: true))
            }
        }

        private static func calendar(
            _ core: any CoreProtocol, _ name: String, _ color: Palette, soft: Bool = false
        ) throws -> String {
            try core.saveCalendar(
                input: CalendarInput(id: nil, name: name, color: color, soft: soft, defaultAlert: .inherit))
        }

        private static func add(
            _ core: any CoreProtocol, _ calendarId: String, _ title: String, _ day: String, _ start: UInt16,
            _ end: UInt16, weekly: [Weekday] = []
        ) throws {
            let recurrence =
                weekly.isEmpty
                ? nil : Recurrence(frequency: .weekly, interval: 1, weekdays: weekly, until: nil, count: nil)
            _ = try core.addEvent(
                input: EventInput(
                    calendarId: calendarId, title: title, allDay: false, start: DayTime(day: day, minute: start),
                    end: DayTime(day: day, minute: end), recurrence: recurrence, alert: .inherit, note: nil),
                fromSuggestion: false)
        }
    }
#endif
