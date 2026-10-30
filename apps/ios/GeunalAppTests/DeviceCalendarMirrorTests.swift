import CoreBridge
import FeatureCalendar
import Foundation
import GeunalCore
import XCTest

/// The device calendar mirror on the real core in a fresh store (m1.md screen 15's second group, decision 5).
@MainActor
final class DeviceCalendarMirrorTests: XCTestCase {
    private final class FakeDeviceCalendars: DeviceCalendarSource {
        var state: DeviceCalendarAccess = .granted
        var answer: DeviceCalendarAccess = .granted
        var phone: [DeviceCalendarInput] = []
        private(set) var asked = 0
        private(set) var reads = 0

        func access() -> DeviceCalendarAccess { state }

        func requestAccess() async -> DeviceCalendarAccess {
            asked += 1
            state = answer
            return state
        }

        func calendars() async -> [DeviceCalendarSummary] {
            phone.map { DeviceCalendarSummary(id: $0.sourceId, name: $0.name, eventCount: $0.events.count) }
        }

        func read(_ ids: [String]) async -> [DeviceCalendarInput] {
            reads += 1
            return phone.filter { ids.contains($0.sourceId) }
        }

        func openSettings() {}
        func observeChanges(_ action: @escaping @MainActor () -> Void) {}
    }

    private func makeCore() throws -> Core {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try Core.open(
            storeDir: directory.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(), secrets: MemorySecretStore())
    }

    private func calendar(_ id: String, _ name: String, events: [String]) -> DeviceCalendarInput {
        DeviceCalendarInput(
            sourceId: id, name: name,
            events: events.enumerated().map { index, title in
                DeviceEventInput(
                    deviceEventId: "\(id)-\(index)", title: title, allDay: false,
                    start: DayTime(day: "2026-11-0\(index + 2)", minute: 9 * 60),
                    end: DayTime(day: "2026-11-0\(index + 2)", minute: 10 * 60))
            })
    }

    private func phone() -> FakeDeviceCalendars {
        let phone = FakeDeviceCalendars()
        phone.phone = [
            calendar("work", "회사", events: ["주간 회의", "면담"]),
            calendar("family", "가족", events: ["제사"]),
        ]
        return phone
    }

    private func mirroredNames(_ core: Core) throws -> [String: UInt32] {
        Dictionary(
            uniqueKeysWithValues: try core.calendars().filter { $0.kind == .device }.map { ($0.name, $0.eventCount) })
    }

    func testImportsEveryCalendarOfThePhone() async throws {
        let core = try makeCore()
        let mirror = DeviceCalendarMirror(core: core, source: phone())

        let count = await mirror.importAll()

        XCTAssertEqual(count, 2)
        XCTAssertEqual(try mirroredNames(core), ["회사": 2, "가족": 1])
        XCTAssertTrue(mirror.isOn)
        XCTAssertEqual(mirror.selected, ["work", "family"])
    }

    func testTurningTheSwitchOffEmptiesTheMirrorAndKeepsTheTicks() async throws {
        let core = try makeCore()
        let mirror = DeviceCalendarMirror(core: core, source: phone())
        await mirror.load()
        await mirror.setOn(true)
        await mirror.toggle("family")
        XCTAssertEqual(try mirroredNames(core), ["회사": 2])

        await mirror.setOn(false)
        XCTAssertEqual(try mirroredNames(core), [:])
        XCTAssertFalse(mirror.isOn)
        XCTAssertEqual(mirror.selected, ["work"])

        await mirror.setOn(true)
        XCTAssertEqual(try mirroredNames(core), ["회사": 2])
    }

    func testUntickingTheLastCalendarTurnsTheSwitchOff() async throws {
        let core = try makeCore()
        let mirror = DeviceCalendarMirror(core: core, source: phone())
        await mirror.load()
        await mirror.setOn(true)

        await mirror.toggle("work")
        await mirror.toggle("family")

        XCTAssertFalse(mirror.isOn)
        XCTAssertEqual(try mirroredNames(core), [:])
    }

    func testAsksForAccessTheFirstTimeTheSwitchTurnsOn() async throws {
        let core = try makeCore()
        let phone = phone()
        phone.state = .notAsked
        let mirror = DeviceCalendarMirror(core: core, source: phone)
        await mirror.load()
        XCTAssertEqual(mirror.calendars, [])

        await mirror.setOn(true)

        XCTAssertEqual(phone.asked, 1)
        XCTAssertEqual(mirror.calendars.map(\.name), ["회사", "가족"])
        XCTAssertEqual(try mirroredNames(core), ["회사": 2, "가족": 1])
    }

    func testARefusedAccessMirrorsNothing() async throws {
        let core = try makeCore()
        let phone = phone()
        phone.state = .notAsked
        phone.answer = .denied
        let mirror = DeviceCalendarMirror(core: core, source: phone)

        await mirror.setOn(true)

        XCTAssertEqual(mirror.access, .denied)
        XCTAssertFalse(mirror.isOn)
        XCTAssertEqual(try mirroredNames(core), [:])
    }

    func testRefreshReadsThePhoneAgainAndDropsADeletedCalendar() async throws {
        let core = try makeCore()
        let phone = phone()
        let mirror = DeviceCalendarMirror(core: core, source: phone)
        _ = await mirror.importAll()

        phone.phone = [calendar("work", "회사", events: ["주간 회의", "면담", "출장"])]
        await mirror.refresh()

        XCTAssertEqual(try mirroredNames(core), ["회사": 3])
    }

    /// Counts the mirror's writes over a store of its own.
    private final class CountingCore: Core, @unchecked Sendable {
        private(set) var writes = 0
        private var mirrored: [DeviceCalendarInput] = []

        init() {
            super.init(noHandle: Core.NoHandle())
        }

        required init(unsafeFromHandle handle: UInt64) {
            fatalError("a fake core never comes from Rust")
        }

        override func calendars() throws -> [CalendarView] {
            mirrored.map {
                CalendarView(
                    id: "device:" + $0.sourceId, name: $0.name, color: nil, kind: .device, soft: false, visible: true,
                    defaultAlert: .inherit, eventCount: UInt32($0.events.count), shiftTemplateCount: 0)
            }
        }

        override func mirrorDeviceCalendars(calendars: [DeviceCalendarInput]) throws -> DeviceMirror {
            writes += 1
            mirrored = calendars
            return DeviceMirror(calendars: UInt32(calendars.count), events: 0)
        }
    }

    func testRefreshWritesNothingWhileThePhoneIsUnchanged() async {
        let core = CountingCore()
        let phone = phone()
        let mirror = DeviceCalendarMirror(core: core, source: phone)
        _ = await mirror.importAll()

        await mirror.refresh()
        await mirror.refresh()
        XCTAssertEqual(core.writes, 1)

        phone.phone[1] = calendar("family", "가족", events: ["제사", "생신"])
        await mirror.refresh()
        XCTAssertEqual(core.writes, 2)
    }

    func testRefreshLeavesAMirrorThatIsOffAlone() async throws {
        let core = try makeCore()
        let phone = phone()
        let mirror = DeviceCalendarMirror(core: core, source: phone)

        await mirror.refresh()

        XCTAssertEqual(phone.reads, 0)
        XCTAssertEqual(try mirroredNames(core), [:])
    }

    func testReadsTimedEventsInKoreanTimeAndAllDayEventsOnThePhonesOwnDays() throws {
        var newYork = Calendar(identifier: .gregorian)
        newYork.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let start = try XCTUnwrap(newYork.date(from: DateComponents(year: 2026, month: 10, day: 30)))
        let lastSecond = try XCTUnwrap(
            newYork.date(from: DateComponents(year: 2026, month: 10, day: 31, hour: 23, minute: 59, second: 59)))
        let nextMidnight = try XCTUnwrap(newYork.date(from: DateComponents(year: 2026, month: 11, day: 1)))

        for end in [lastSecond, nextMidnight] {
            let allDay = DeviceEventDates.input(
                id: "a", title: "여행", allDay: true, start: start, end: end, local: newYork)
            XCTAssertEqual(allDay.start, DayTime(day: "2026-10-30", minute: 0))
            XCTAssertEqual(allDay.end, DayTime(day: "2026-10-31", minute: 0))
        }

        // 09:00 UTC is 18:00 in Korea, whatever the phone's own time zone.
        let nine = Date(timeIntervalSince1970: 1_793_350_800)
        let timed = DeviceEventDates.input(
            id: "t", title: "통화", allDay: false, start: nine, end: nine.addingTimeInterval(3600), local: newYork)
        XCTAssertEqual(timed.start, DayTime(day: "2026-10-30", minute: 18 * 60))
        XCTAssertEqual(timed.end, DayTime(day: "2026-10-30", minute: 19 * 60))
        XCTAssertEqual(
            DeviceEventDates.occurrenceId(event: "E1", start: nine), "E1@1793350800")
    }
}
