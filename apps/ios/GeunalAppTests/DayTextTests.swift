import FeatureCalendar
import XCTest

final class DayTextTests: XCTestCase {
    func testNamesTheNearDays() {
        XCTAssertEqual(DayText.relativeDay("2026-10-25", today: "2026-10-25"), "오늘")
        XCTAssertEqual(DayText.relativeDay("2026-10-26", today: "2026-10-25"), "내일")
        XCTAssertEqual(DayText.relativeDay("2026-10-27", today: "2026-10-25"), "모레")
        XCTAssertEqual(DayText.relativeDay("2026-11-07", today: "2026-10-25"), "11월 7일(토)")
        XCTAssertEqual(DayText.relativeDay("2027-01-03", today: "2026-10-25"), "2027년 1월 3일")
    }

    func testWritesTimesWithTwentyFourHours() {
        XCTAssertEqual(DayText.range(start: 540, end: 1080), "09:00–18:00")
        XCTAssertEqual(DayText.clock(1440), "24:00")
    }

    func testSaysHowLongUntilWithTwoUnitsAtMost() {
        let now = Date(timeIntervalSince1970: 1_793_000_000)
        XCTAssertEqual(DayText.until(now.addingTimeInterval(13_200), now: now), "3시간 40분 뒤")
        XCTAssertEqual(DayText.until(now.addingTimeInterval(97_200), now: now), "1일 3시간 뒤")
        XCTAssertEqual(DayText.until(now.addingTimeInterval(240), now: now), "4분 뒤")
        XCTAssertNil(DayText.until(now, now: now))
    }

    func testComputesDaysInKoreanTime() {
        // 2026-10-26 15:30 UTC is already the 27th in Seoul.
        XCTAssertEqual(KoreanCalendar.day(of: Date(timeIntervalSince1970: 1_793_028_600)), "2026-10-27")
        XCTAssertEqual(KoreanCalendar.weekday(of: "2026-11-07"), 7)
    }
}
