import CoreBridge
import FeatureCalendar
import Foundation
import GeunalCore
import XCTest

/// The nearest alerts stay scheduled, and a refresh touches only what changed (docs/parts/ios.md §5).
@MainActor
final class AlertSchedulerTests: XCTestCase {
    private final class AlertFakeCore: Core, @unchecked Sendable {
        var alerts: [ScheduledAlert] = []
        var persons: [Person] = []

        init() {
            super.init(noHandle: Core.NoHandle())
        }

        required init(unsafeFromHandle handle: UInt64) {
            fatalError("a fake core never comes from Rust")
        }

        override func scheduledAlerts(limit: UInt32) throws -> [ScheduledAlert] { Array(alerts.prefix(Int(limit))) }
        override func people() throws -> [Person] { persons }
    }

    private final class FakeCenter: AlertCenter {
        var allows = true
        var pending: [PlannedAlert] = []
        private(set) var added: [String] = []
        private(set) var cancelled: [String] = []

        func allowsAlerts() async -> Bool { allows }
        func scheduled() async -> [PlannedAlert] { pending }

        func schedule(_ alert: PlannedAlert) async throws {
            added.append(alert.id)
            pending.removeAll { $0.id == alert.id }
            pending.append(alert)
        }

        func cancel(_ ids: [String]) {
            cancelled += ids
            pending.removeAll { ids.contains($0.id) }
        }

        func removeAll() {
            pending = []
        }
    }

    private let evening = KoreanCalendar.start(of: "2026-10-30")!.addingTimeInterval(17 * 3600)

    private func dinner(_ title: String = "가족 · 저녁") -> ScheduledAlert {
        ScheduledAlert(
            id: "event:e1:k:60", fireAt: evening, target: .event(eventId: "e1", occurrence: nil), title: title,
            start: DayTime(day: "2026-10-30", minute: 18 * 60), allDay: false,
            end: DayTime(day: "2026-10-30", minute: 20 * 60))
    }

    private func birthday(_ personId: String) -> ScheduledAlert {
        ScheduledAlert(
            id: "birthday:\(personId)", fireAt: evening, target: .birthday(personId: personId), title: "민수",
            start: DayTime(day: "2026-10-30", minute: 0), allDay: true)
    }

    func testSchedulesTheCoresAlertsInTheirWords() async {
        let core = AlertFakeCore()
        core.alerts = [dinner()]
        let center = FakeCenter()

        await AlertScheduler(core: core, center: center).refresh()

        XCTAssertEqual(
            center.pending,
            [
                PlannedAlert(
                    id: "alert.event:e1:k:60", fireAt: evening, title: "가족 · 저녁", body: "1시간 뒤 · 18:00–20:00",
                    route: .event(eventId: "e1", occurrence: nil))
            ])
    }

    /// The real core gives an event's alert its end (core #55), so the body names the whole time.
    func testTheCoresAlertNamesTheEventsEnd() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let core = try Core.open(
            storeDir: directory.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(), secrets: MemorySecretStore())
        let calendar = try XCTUnwrap(core.calendars().first { $0.kind == .own }?.id)
        let tomorrow = try XCTUnwrap(KoreanCalendar.adding(days: 1, to: KoreanCalendar.day(of: Date())))
        _ = try core.addEvent(
            input: EventInput(
                calendarId: calendar, title: "저녁", allDay: false, start: DayTime(day: tomorrow, minute: 18 * 60),
                end: DayTime(day: tomorrow, minute: 20 * 60), recurrence: nil, alert: .before(minutes: 60), note: nil),
            fromSuggestion: false)
        let center = FakeCenter()

        await AlertScheduler(core: core, center: center).refresh()

        XCTAssertEqual(center.pending.map(\.body), ["1시간 뒤 · 18:00–20:00"])
    }

    func testRemovesEveryAlertOnASignOut() async {
        let core = AlertFakeCore()
        core.alerts = [dinner()]
        let center = FakeCenter()
        let scheduler = AlertScheduler(core: core, center: center)
        await scheduler.refresh()

        scheduler.removeAll()

        XCTAssertEqual(center.pending, [])
    }

    func testRefreshesAskedForTogetherScheduleEachAlertOnce() async {
        let core = AlertFakeCore()
        core.alerts = [dinner(), birthday("p1")]
        let center = FakeCenter()
        let scheduler = AlertScheduler(core: core, center: center)

        async let first: Void = scheduler.refresh()
        async let second: Void = scheduler.refresh()
        async let third: Void = scheduler.refresh()
        _ = await (first, second, third)

        XCTAssertEqual(center.added.sorted(), ["alert.birthday:p1", "alert.event:e1:k:60"])
        XCTAssertEqual(center.pending.count, 2)
    }

    func testLeavesAnUnchangedCalendarAlone() async {
        let core = AlertFakeCore()
        core.alerts = [dinner()]
        let center = FakeCenter()
        let scheduler = AlertScheduler(core: core, center: center)
        await scheduler.refresh()

        await scheduler.refresh()

        XCTAssertEqual(center.added, ["alert.event:e1:k:60"])
        XCTAssertEqual(center.cancelled, [])
    }

    func testReplacesARenamedEventAndDropsAGoneOne() async {
        let core = AlertFakeCore()
        core.alerts = [dinner(), birthday("p1")]
        let center = FakeCenter()
        let scheduler = AlertScheduler(core: core, center: center)
        await scheduler.refresh()

        core.alerts = [dinner("가족 · 외식")]
        await scheduler.refresh()

        XCTAssertEqual(center.pending.map(\.title), ["가족 · 외식"])
        XCTAssertEqual(Set(center.cancelled), ["alert.event:e1:k:60", "alert.birthday:p1"])
    }

    func testCancelsEverythingWhenAlertsAreOff() async {
        let core = AlertFakeCore()
        core.alerts = [dinner()]
        let center = FakeCenter()
        let scheduler = AlertScheduler(core: core, center: center)
        await scheduler.refresh()

        center.allows = false
        await scheduler.refresh()

        XCTAssertEqual(center.pending, [])
    }

    func testAddsTheLunarDateToALunarBirthday() async {
        let core = AlertFakeCore()
        core.alerts = [birthday("p1")]
        core.persons = [
            Person(
                id: "p1", name: "민수",
                birthday: Birthday(month: 10, day: 3, year: nil, calendar: .lunar(leapMonth: false)),
                nextBirthday: "2026-10-30", daysUntil: 0, source: .manual, readOnly: false, alert: .inherit,
                suggest: true)
        ]
        let center = FakeCenter()

        await AlertScheduler(core: core, center: center).refresh()

        XCTAssertEqual(center.pending.first?.title, "민수 생일")
        XCTAssertEqual(center.pending.first?.body, "오늘이에요 · 음력 10월 3일")
        XCTAssertEqual(center.pending.first?.route, .person(personId: "p1"))
    }

    func testARouteSurvivesTheNotificationsUserInfo() {
        let routes: [AlertRoute] = [
            .event(eventId: "e1", occurrence: "2026-10-30"), .event(eventId: "e2", occurrence: nil),
            .person(personId: "p1"), .week(day: "2026-10-30"),
        ]
        for route in routes {
            XCTAssertEqual(AlertRoute(userInfo: route.userInfo), route)
        }
        XCTAssertNil(AlertRoute(userInfo: ["route": "group"]))
    }
}
