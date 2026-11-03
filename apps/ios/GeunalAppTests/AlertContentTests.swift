import FeatureCalendar
import GeunalCore
import XCTest

/// The local notifications' words (docs/design/tone.md "알림 문구", m1.md decisions 32–34), in Korean time.
final class AlertContentTests: XCTestCase {
    /// 2026-10-30 is a Friday.
    private func at(_ day: String, _ hour: Int, _ minute: Int = 0) -> Date {
        KoreanCalendar.start(of: day)!.addingTimeInterval(TimeInterval(hour * 3600 + minute * 60))
    }

    private func alert(
        _ target: AlertTarget, title: String = "가족 · 저녁", firesAt: Date, start: DayTime, end: DayTime? = nil,
        allDay: Bool = false
    ) -> ScheduledAlert {
        ScheduledAlert(
            id: "a", fireAt: firesAt, target: target, title: title, start: start, allDay: allDay, end: end)
    }

    private let dinner = AlertTarget.event(eventId: "e1", occurrence: nil)

    func testAnEventTheSameDaySaysHowLongUntilItStarts() {
        let start = DayTime(day: "2026-10-30", minute: 18 * 60)
        let soon = alert(
            dinner, firesAt: at("2026-10-30", 17), start: start, end: DayTime(day: "2026-10-30", minute: 20 * 60))

        let text = AlertContent.text(for: soon)

        XCTAssertEqual(text, AlertContent.Text(title: "가족 · 저녁", body: "1시간 뒤 · 18:00–20:00"))
        let withoutEnd = alert(dinner, firesAt: at("2026-10-30", 17), start: start)
        XCTAssertEqual(AlertContent.text(for: withoutEnd).body, "1시간 뒤 · 18:00")
    }

    func testAnEventAtItsStartSaysNow() {
        let start = DayTime(day: "2026-10-30", minute: 18 * 60)

        let text = AlertContent.text(for: alert(dinner, firesAt: at("2026-10-30", 18), start: start))

        XCTAssertEqual(text.body, "지금 · 18:00")
    }

    func testAnEventTheDayBeforeSaysTomorrow() {
        let start = DayTime(day: "2026-10-30", minute: 18 * 60)

        let text = AlertContent.text(
            for: alert(
                dinner, firesAt: at("2026-10-29", 18), start: start, end: DayTime(day: "2026-10-30", minute: 20 * 60)))

        XCTAssertEqual(text.body, "내일 · 18:00–20:00")
    }

    func testAnAllDayEventSaysItsDayAndWhenItEnds() {
        let start = DayTime(day: "2026-10-30", minute: 0)
        func eve(through last: String?) -> ScheduledAlert {
            alert(
                dinner, title: "가족 · 여행", firesAt: at("2026-10-29", 18), start: start,
                end: last.map { DayTime(day: $0, minute: 0) }, allDay: true)
        }

        XCTAssertEqual(AlertContent.text(for: eve(through: nil)).body, "내일 · 종일")
        XCTAssertEqual(AlertContent.text(for: eve(through: "2026-10-30")).body, "내일 · 종일")
        XCTAssertEqual(AlertContent.text(for: eve(through: "2026-11-09")).body, "내일부터 11/9(월)까지")
        let morning = alert(dinner, firesAt: at("2026-10-30", 9), start: start, allDay: true)
        XCTAssertEqual(AlertContent.text(for: morning).body, "오늘 · 종일")
    }

    func testABirthdayCountsDownWithItsShortDate() {
        let minsu = AlertTarget.birthday(personId: "p1")
        let day = DayTime(day: "2026-11-09", minute: 0)
        let onTheDay = alert(minsu, title: "민수", firesAt: at("2026-11-09", 9), start: day, allDay: true)
        let dayBefore = alert(minsu, title: "민수", firesAt: at("2026-11-08", 9), start: day, allDay: true)
        let threeDays = alert(minsu, title: "민수", firesAt: at("2026-11-06", 9), start: day, allDay: true)

        XCTAssertEqual(AlertContent.text(for: onTheDay), AlertContent.Text(title: "민수 생일", body: "오늘이에요"))
        XCTAssertEqual(AlertContent.text(for: dayBefore).body, "내일 · 11/9(월)")
        XCTAssertEqual(AlertContent.text(for: threeDays).body, "D-3 · 11/9(월)")
        XCTAssertEqual(
            AlertContent.text(for: onTheDay, lunarBirthday: "음력 10월 3일").body, "오늘이에요 · 음력 10월 3일")
    }

    func testALeaveSuggestionNamesTheDayOffAndTheHolidays() {
        let bridge = LeaveBridge(leaveDay: "2026-10-30", firstDay: "2026-10-30", lastDay: "2026-11-02", daysOff: 4)
        let suggestion = alert(
            .leaveBridge(bridge: bridge), title: "", firesAt: at("2026-10-09", 9),
            start: DayTime(day: "2026-10-30", minute: 0), allDay: true)

        XCTAssertEqual(
            AlertContent.text(for: suggestion),
            AlertContent.Text(title: "연차 1일로 4일 연휴", body: "10/30(금) 하루 쉬면 10/30(금)–11/2(월) 쉬어요"))
    }
}
