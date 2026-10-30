import FeatureCalendar
import GeunalCore
import XCTest

final class CalendarTextTests: XCTestCase {
    func testTitlesADayWithItsWeekday() {
        XCTAssertEqual(DayText.title("2026-10-30", today: "2026-10-26"), "10월 30일 금요일")
        XCTAssertEqual(DayText.title("2027-01-03", today: "2026-10-26"), "2027년 1월 3일 일요일")
        XCTAssertEqual(DayText.agendaHeading("2026-10-25", today: "2026-10-25"), "오늘 · 10월 25일 일요일")
        XCTAssertEqual(DayText.agendaHeading("2026-10-26", today: "2026-10-25"), "10월 26일 월요일")
    }

    func testHeadsTheMonthAndTheWeek() {
        XCTAssertEqual(DayText.monthHeading("2026-10").caption, "2026년")
        XCTAssertEqual(DayText.monthHeading("2026-10").title, "10월")
        XCTAssertEqual(DayText.weekHeading(start: "2026-10-25").caption, "2026년 10월")
        XCTAssertEqual(DayText.weekHeading(start: "2026-10-25").title, "25–31일")
        XCTAssertEqual(DayText.weekHeading(start: "2026-11-29").title, "11월 29일–12월 5일")
        XCTAssertEqual(DayText.weekHeading(start: "2026-12-27").caption, "2026–2027년")
    }

    func testWritesChipTimesInHoursWhenItCan() {
        XCTAssertEqual(DayText.chipTime(start: 1140, end: 1260), "19–21")
        XCTAssertEqual(DayText.chipTime(start: 540, end: 1080), "09–18")
        XCTAssertEqual(DayText.chipTime(start: 1320, end: 120), "22–02")
        XCTAssertEqual(DayText.chipTime(start: 1320, end: 1440), "22–24")
        XCTAssertEqual(DayText.chipTime(start: 570, end: 660), "09:30–11:00")
    }

    func testPicksTheObjectParticleByTheLastSound() {
        XCTAssertTrue(Josa.endsInConsonant("카페 마감"))
        XCTAssertFalse(Josa.endsInConsonant("팀플 회의"))
        XCTAssertTrue(Josa.endsInConsonant("회의(온라인)"))
        XCTAssertFalse(Josa.endsInConsonant("PT"))
        XCTAssertTrue(Josa.endsInConsonant("Gym"))
        XCTAssertTrue(Josa.endsInConsonant("3"))
        XCTAssertFalse(Josa.endsInConsonant("2"))
        XCTAssertFalse(Josa.endsInConsonant("🎉"))
        XCTAssertEqual(Josa.object(after: "카페 마감"), "을")
        XCTAssertEqual(Josa.object(after: "PT"), "를")
    }

    func testDescribesRepeatRules() {
        func rule(_ frequency: Frequency, every interval: UInt16 = 1, on days: [Weekday] = []) -> Recurrence {
            Recurrence(frequency: frequency, interval: interval, weekdays: days, until: nil, count: nil)
        }
        // 2026-10-30 is a Friday.
        XCTAssertEqual(RecurrenceText.describe(rule(.daily), startDay: "2026-10-30"), "매일")
        XCTAssertEqual(RecurrenceText.describe(rule(.weekly), startDay: "2026-10-30"), "매주 금요일")
        XCTAssertEqual(
            RecurrenceText.describe(rule(.weekly, on: [.wednesday, .monday]), startDay: "2026-10-26"), "매주 월·수")
        XCTAssertEqual(
            RecurrenceText.describe(rule(.weekly, every: 2, on: [.monday]), startDay: "2026-10-26"), "2주마다 월요일")
        XCTAssertEqual(RecurrenceText.describe(rule(.monthly), startDay: "2026-10-15"), "매월 15일")
        XCTAssertEqual(RecurrenceText.describe(rule(.yearly), startDay: "2026-10-15"), "매년")
    }

    func testDoesDayArithmeticInKoreanTime() {
        XCTAssertEqual(KoreanCalendar.adding(days: 7, to: "2026-10-26"), "2026-11-02")
        XCTAssertEqual(KoreanCalendar.adding(months: 3, to: "2026-11"), "2027-02")
        XCTAssertEqual(KoreanCalendar.adding(months: -11, to: "2026-10"), "2025-11")
        XCTAssertEqual(KoreanCalendar.weekStart(of: "2026-10-30"), "2026-10-25")
        XCTAssertEqual(KoreanCalendar.weekStart(of: "2026-10-25"), "2026-10-25")
        XCTAssertEqual(Weekday(number: 6), .friday)
        XCTAssertEqual(Weekday.saturday.number, 7)
    }
}
