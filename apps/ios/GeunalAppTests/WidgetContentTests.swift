import CoreBridge
import FeatureCalendar
import Foundation
import GeunalCore
import HomeWidget
import XCTest

/// What the widgets show from the snapshot the app writes (rust-core.md §3 "위젯 스냅샷", m1.md screen 14), on the
/// real core.
final class WidgetContentTests: XCTestCase {
    private func makeCore() throws -> Core {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try Core.open(
            storeDir: directory.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(), secrets: MemorySecretStore())
    }

    @discardableResult
    private func add(
        _ core: Core, _ title: String, on day: String, from start: UInt16, to end: UInt16
    ) throws
        -> String
    {
        let calendarId = try XCTUnwrap(core.calendars().first { $0.kind == .own }?.id)
        return try core.addEvent(
            input: EventInput(
                calendarId: calendarId, title: title, allDay: false, start: DayTime(day: day, minute: start),
                end: DayTime(day: day, minute: end), recurrence: nil, alert: .inherit, note: nil),
            fromSuggestion: false
        ).eventId
    }

    private func titles(_ events: [WidgetSnapshot.Event]?) -> [String]? {
        events?.map { "\($0.title) \($0.start ?? "-")–\($0.end ?? "-")" }
    }

    func testShowsTheCoresTodayAndTomorrow() throws {
        let core = try makeCore()
        let today = KoreanCalendar.day(of: Date())
        let tomorrow = try XCTUnwrap(KoreanCalendar.adding(days: 1, to: today))
        let dinner = try add(core, "가족 · 저녁", on: today, from: 1080, to: 1200)
        try add(core, "전공 수업", on: tomorrow, from: 600, to: 720)

        let content = WidgetContent.make(WidgetSnapshot.read(Data(try core.widgetSnapshot().utf8)), day: today)

        guard case .events(let shownToday, let shownTomorrow) = content.state else {
            return XCTFail("expected events, got \(content.state)")
        }
        XCTAssertEqual(titles(shownToday), ["가족 · 저녁 18:00–20:00"])
        XCTAssertEqual(titles(shownTomorrow), ["전공 수업 10:00–12:00"])
        XCTAssertEqual(shownToday.first?.color, "tangerine")
        // The core names each event (core #55), so tapping one opens it in the editor (m1.md 2.6).
        XCTAssertEqual(shownToday.first.flatMap(WidgetLink.event), .event(eventId: dinner, occurrence: nil))
    }

    private let snapshot = WidgetSnapshot(
        day: "2026-10-25",
        today: [WidgetSnapshot.Event(title: "가족 · 저녁", color: "blossom", allDay: false, start: "18:00", end: "20:00")],
        tomorrow: [WidgetSnapshot.Event(title: "전공 수업", color: "sea", allDay: false, start: "10:00", end: "12:00")])

    func testShowsYesterdaysTomorrowOnceMidnightPassed() {
        let content = WidgetContent.make(.snapshot(snapshot), day: "2026-10-26")

        XCTAssertEqual(content.state, .events(today: snapshot.tomorrow, tomorrow: nil))
    }

    func testAsksToOpenTheAppForAnOldOrUnreadableSnapshot() {
        XCTAssertEqual(WidgetContent.make(.snapshot(snapshot), day: "2026-10-27").state, .openApp)
        XCTAssertEqual(WidgetSnapshot.read(Data(#"{"v": 2, "day": "2026-10-25"}"#.utf8)), .unreadable)
        XCTAssertEqual(WidgetSnapshot.read(Data("not json".utf8)), .unreadable)
        XCTAssertEqual(WidgetContent.make(.unreadable, day: "2026-10-25").state, .openApp)
    }

    func testAsksToStartWithoutASnapshot() {
        XCTAssertEqual(WidgetSnapshot.read(nil), .missing)
        XCTAssertEqual(WidgetContent.make(.missing, day: "2026-10-25").state, .start)
    }

    func testIgnoresKeysItDoesNotKnow() throws {
        let json = #"""
            {"v": 1, "day": "2026-10-25", "next_appointment": null, "later": [1],
             "today": [{"event_id": "e1", "occurrence": "2026-10-25T18:00", "title": "가족 · 저녁", "color": null,
                        "all_day": true, "start": null, "end": null, "badge": "x"}],
             "tomorrow": []}
            """#
        guard case .snapshot(let read) = WidgetSnapshot.read(Data(json.utf8)) else {
            return XCTFail("expected a snapshot")
        }
        XCTAssertEqual(
            read.today,
            [
                WidgetSnapshot.Event(
                    eventId: "e1", occurrence: "2026-10-25T18:00", title: "가족 · 저녁", color: nil, allDay: true,
                    start: nil, end: nil)
            ])
    }

    func testLinksOpenTodayOrTheirEvent() throws {
        for link in [
            WidgetLink.today, .event(eventId: "e1", occurrence: nil),
            .event(eventId: "e 2", occurrence: "2026-10-25T18:00"),
        ] {
            XCTAssertEqual(WidgetLink(url: link.url), link)
        }
        XCTAssertNil(WidgetLink(url: try XCTUnwrap(URL(string: "kakao0123://oauth?code=x"))))
        XCTAssertNil(WidgetLink.event(snapshot.today[0]))
    }

    func testTurnsThePageAtKoreanMidnight() {
        // 2026-10-25 23:30 in Korea is 14:30 UTC.
        let late = Date(timeIntervalSince1970: 1_792_938_600)
        XCTAssertEqual(WidgetDays.day(of: late), "2026-10-25")
        XCTAssertEqual(WidgetDays.day(of: WidgetDays.nextMidnight(after: late)), "2026-10-26")
        XCTAssertEqual(WidgetDays.nextMidnight(after: late).timeIntervalSince(late), 1800)
    }
}
