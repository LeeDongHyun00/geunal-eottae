import FeatureCalendar
import GeunalCore
import XCTest

// docs/design/m1.md decision 44: overlapping events are one group, as a chain; touching ones are not.
final class WeekOverlapTests: XCTestCase {
    private let day = "2026-10-29"

    private func block(_ title: String, _ start: Int, _ end: Int) -> TimedBlock {
        let event = EventItem(
            eventId: title, occurrence: nil, calendarId: "c1", calendarName: "개인", color: .blossom, title: title,
            allDay: false, start: DayTime(day: day, minute: UInt16(start)), end: DayTime(day: day, minute: UInt16(end)),
            soft: false, source: .own, recurrence: nil)
        return TimedBlock(event: event, startMinute: UInt16(start), endMinute: UInt16(end), column: 0, columns: 1)
    }

    private func groups(_ items: [WeekItem]) -> [OverlapGroup] {
        items.compactMap {
            if case .group(let group) = $0 { return group }
            return nil
        }
    }

    func testAChainOfOverlapsIsOneGroupFromItsFirstStartToItsLastEnd() throws {
        let items = WeekOverlap.items(
            day: day, blocks: [block("과제", 1020, 1080), block("팀플 회의", 960, 1020), block("동아리 회의", 960, 1080)])

        let group = try XCTUnwrap(groups(items).first)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(group.startMinute, 960)
        XCTAssertEqual(group.endMinute, 1080)
        // By start, the longer first on the same start.
        XCTAssertEqual(group.blocks.map(\.event.title), ["동아리 회의", "팀플 회의", "과제"])
        XCTAssertEqual(group.summary, "16:00–18:00 · 겹치는 일정 3개")
        XCTAssertEqual(group.spokenLabel(), "10월 29일 16:00부터 18:00까지 겹치는 일정 3개: 동아리 회의, 팀플 회의, 과제")
        XCTAssertEqual(group.scale, [960, 1020, 1080])
    }

    func testEventsThatOnlyTouchStayApart() {
        let items = WeekOverlap.items(day: day, blocks: [block("팀플 회의", 960, 1020), block("과제", 1020, 1080)])

        XCTAssertEqual(items.count, 2)
        XCTAssertTrue(groups(items).isEmpty)
    }

    func testAShortEventAtTheStartOfALongOneDrawsOnlyItsHour() throws {
        let items = WeekOverlap.items(day: day, blocks: [block("교수 면담", 600, 660), block("전공 수업", 600, 840)])

        let group = try XCTUnwrap(groups(items).first)
        XCTAssertEqual(group.blocks.map(\.event.title), ["전공 수업", "교수 면담"])
        XCTAssertEqual(group.span(of: group.blocks[0]), 0...1)
        XCTAssertEqual(group.span(of: group.blocks[1]), 0...0.25)
    }

    func testLoneEventsAroundAGroupStayAsTheyAre() {
        let items = WeekOverlap.items(
            day: day,
            blocks: [block("헬스", 1140, 1200), block("교양 수업", 540, 630), block("치과", 600, 660)])

        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(groups(items).first?.blocks.count, 2)
        guard case .single(let last) = items.last else { return XCTFail("the evening event stands alone") }
        XCTAssertEqual(last.event.title, "헬스")
    }
}
