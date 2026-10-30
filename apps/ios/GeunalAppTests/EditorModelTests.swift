import CoreBridge
import FeatureCalendar
import GeunalCore
import XCTest

/// The editor, the shift stamp screen and the template sheet on the real core in a fresh store.
@MainActor
final class EditorModelTests: XCTestCase {
    private func makeCore() throws -> Core {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try Core.open(
            storeDir: directory.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(), secrets: MemorySecretStore())
    }

    private let day = "2026-10-30"

    func testANewEventKeepsItsLengthWhenTheStartMoves() async throws {
        let core = try makeCore()
        let model = EditorModel(newOn: day, core: core)
        await model.load()

        XCTAssertTrue(model.isNew)
        XCTAssertEqual(model.selectedCalendar?.name, "개인")
        XCTAssertFalse(model.canSave)
        model.title = "카페 마감"
        model.setStart(day: day, minute: 1020)
        model.setEnd(day: day, minute: 1320)
        XCTAssertTrue(model.canSave)

        // Five hours later, still five hours long: it now ends at 03:00 the next day.
        model.setStart(day: day, minute: 1320)
        XCTAssertEqual(model.endDay, "2026-10-31")
        XCTAssertEqual(model.endMinute, 180)

        model.setEnd(day: day, minute: 1000)
        XCTAssertFalse(model.hasValidRange)
    }

    func testSavesANewEventAndOpensItAgain() async throws {
        let core = try makeCore()
        let new = EditorModel(newOn: day, core: core)
        await new.load()
        new.title = "카페 마감"
        new.setStart(day: day, minute: 1020)
        new.setEnd(day: day, minute: 1440)
        new.recurrence = Recurrence(frequency: .weekly, interval: 1, weekdays: [], until: nil, count: nil)

        let saved = await new.save()

        XCTAssertTrue(saved)
        let item = try XCTUnwrap(core.calendarAgenda(fromDay: day, days: 1).first?.events.first)
        XCTAssertEqual(item.title, "카페 마감")
        let opened = EditorModel(eventId: item.eventId, occurrence: item.occurrence, core: core)
        await opened.load()
        XCTAssertEqual(opened.title, "카페 마감")
        XCTAssertEqual(opened.startMinute, 1020)
        // Midnight at the end reads as 24:00 of the same day.
        XCTAssertEqual(opened.endDay, day)
        XCTAssertEqual(opened.endMinute, 1440)
        XCTAssertTrue(opened.wasRepeating)
        XCTAssertTrue(opened.asksScope)
    }

    func testChangesOnlyThisOccurrenceOfARepeatingEvent() async throws {
        let core = try makeCore()
        let calendar = try XCTUnwrap(core.calendars().first { $0.kind == .own }?.id)
        _ = try core.addEvent(
            input: EventInput(
                calendarId: calendar, title: "전공 수업", allDay: false, start: DayTime(day: "2026-10-26", minute: 600),
                end: DayTime(day: "2026-10-26", minute: 720),
                recurrence: Recurrence(frequency: .weekly, interval: 1, weekdays: [], until: nil, count: nil),
                alert: .inherit, note: nil),
            fromSuggestion: false)
        let second = try XCTUnwrap(core.calendarAgenda(fromDay: "2026-11-02", days: 1).first?.events.first)

        let model = EditorModel(eventId: second.eventId, occurrence: second.occurrence, core: core)
        await model.load()
        model.title = "휴강"
        let saved = await model.save(scope: .thisOnly)

        XCTAssertTrue(saved)
        XCTAssertEqual(try core.calendarAgenda(fromDay: "2026-11-02", days: 1).first?.events.map(\.title), ["휴강"])
        XCTAssertEqual(try core.calendarAgenda(fromDay: "2026-11-09", days: 1).first?.events.map(\.title), ["전공 수업"])
    }

    func testDeletesAnEvent() async throws {
        let core = try makeCore()
        let new = EditorModel(newOn: day, core: core)
        await new.load()
        new.title = "치과"
        _ = await new.save()
        let item = try XCTUnwrap(core.calendarAgenda(fromDay: day, days: 1).first?.events.first)

        let model = EditorModel(eventId: item.eventId, occurrence: item.occurrence, core: core)
        await model.load()
        XCTAssertFalse(model.asksScope)
        let deleted = await model.delete()

        XCTAssertTrue(deleted)
        XCTAssertTrue(try core.calendarAgenda(fromDay: day, days: 1).isEmpty)
    }

    func testAllDayTurnsOffToNineOClock() async throws {
        let model = EditorModel(
            newOn: day,
            input: EventInput(
                calendarId: "c", title: "연차", allDay: true, start: DayTime(day: day, minute: 0),
                end: DayTime(day: day, minute: 0), recurrence: nil, alert: .inherit, note: nil),
            core: try makeCore())

        XCTAssertTrue(model.allDay)
        XCTAssertTrue(model.hasValidRange)
        model.setAllDay(false)
        XCTAssertEqual(model.startMinute, 540)
        XCTAssertEqual(model.endMinute, 600)
    }

    func testStampsShiftsAsADraftUntilDone() async throws {
        let core = try makeCore()
        let open = TemplateEditModel(core: core)
        await open.load()
        open.name = "오픈"
        open.startMinute = 480
        open.endMinute = 780
        let openSaved = await open.save()
        let openId = try XCTUnwrap(openSaved)
        let close = TemplateEditModel(core: core)
        close.name = "마감"
        close.startMinute = 1320
        close.endMinute = 120
        XCTAssertTrue(close.crossesMidnight)
        XCTAssertEqual(close.lengthMinutes, 240)
        let closeSaved = await close.save()
        let closeId = try XCTUnwrap(closeSaved)

        let model = ShiftStampModel(month: "2026-11", core: core)
        await model.load()
        XCTAssertEqual(model.templates.map(\.name), ["오픈", "마감"])
        XCTAssertEqual(model.selectedTemplateId, openId)
        XCTAssertEqual(model.weeks.count, 5)

        model.tap(day: "2026-11-07")
        model.tap(day: "2026-11-14")
        model.selectedTemplateId = closeId
        model.tap(day: "2026-11-14")
        model.tap(day: "2026-11-20")
        model.tap(day: "2026-11-20")
        XCTAssertEqual(model.countThisMonth, 2)
        XCTAssertEqual(model.templates(on: "2026-11-14").map(\.name), ["마감"])
        XCTAssertTrue(try core.shiftDays(month: "2026-11").isEmpty, "nothing is saved before 완료")

        let done = await model.commit()

        XCTAssertTrue(done)
        XCTAssertFalse(model.hasChanges)
        let days = try core.shiftDays(month: "2026-11")
        XCTAssertEqual(days.map(\.day), ["2026-11-07", "2026-11-14"])
        let shift = try XCTUnwrap(core.calendarAgenda(fromDay: "2026-11-14", days: 1).first?.events.first)
        XCTAssertEqual(shift.title, "마감")
        XCTAssertEqual(shift.end, DayTime(day: "2026-11-15", minute: 120))
    }

    func testKnowsTheShiftsOnTheNextMonthsDaysOfTheGrid() async throws {
        let core = try makeCore()
        let edit = TemplateEditModel(core: core)
        edit.name = "오픈"
        let saved = await edit.save()
        let id = try XCTUnwrap(saved)
        try core.setShiftDays(days: [ShiftDay(day: "2026-12-01", templateIds: [id])])

        let model = ShiftStampModel(month: "2026-11", core: core)
        await model.load()
        XCTAssertTrue(model.weeks.joined().contains("2026-12-01"))
        XCTAssertEqual(model.templates(on: "2026-12-01").map(\.name), ["오픈"])

        // The same template again takes the shift off, as it does inside the month.
        model.tap(day: "2026-12-01")

        XCTAssertEqual(model.changedDays, [ShiftDay(day: "2026-12-01", templateIds: [])])
        await model.show(month: "2026-12")
        XCTAssertEqual(model.changedDays, [ShiftDay(day: "2026-12-01", templateIds: [])])
    }

    func testATemplateGoesButItsShiftsStay() async throws {
        let core = try makeCore()
        let edit = TemplateEditModel(core: core)
        edit.name = "미들"
        let saved = await edit.save()
        let id = try XCTUnwrap(saved)
        try core.setShiftDays(days: [ShiftDay(day: "2026-11-04", templateIds: [id])])
        let template = try XCTUnwrap(core.shiftTemplates().first)

        let model = TemplateEditModel(template: template, core: core)
        XCTAssertFalse(model.isNew)
        let deleted = await model.delete()

        XCTAssertTrue(deleted)
        XCTAssertTrue(try core.shiftTemplates().isEmpty)
        XCTAssertEqual(try core.calendarAgenda(fromDay: "2026-11-04", days: 1).first?.events.map(\.title), ["미들"])
    }
}
