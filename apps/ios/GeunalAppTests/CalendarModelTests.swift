import CoreBridge
import DesignSystem
import FeatureCalendar
import GeunalCore
import XCTest

/// The calendar screens' models on the real core in a fresh store: the M1 calendar runs entirely on the device, so
/// these check the screens against what the core really answers.
@MainActor
final class CalendarModelTests: XCTestCase {
    private struct Fixture {
        let core: Core
        let today: String
    }

    private func makeFixture() throws -> Fixture {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let core = try Core.open(
            storeDir: directory.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(), secrets: MemorySecretStore())
        return Fixture(core: core, today: KoreanCalendar.day(of: Date()))
    }

    private func personal(_ core: Core) throws -> String {
        try XCTUnwrap(core.calendars().first { $0.kind == .own }?.id)
    }

    private func event(
        _ calendarId: String, _ title: String, on day: String, from start: UInt16 = 540, to end: UInt16 = 600
    )
        -> EventInput
    {
        EventInput(
            calendarId: calendarId, title: title, allDay: false, start: DayTime(day: day, minute: start),
            end: DayTime(day: day, minute: end), recurrence: nil, alert: .inherit, note: nil)
    }

    /// Waits for a toast the actions show after their core call.
    private func waitForToast(_ toasts: ToastCenter, _ message: String) async throws {
        for _ in 0..<200 where toasts.current?.message != message {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(toasts.current?.message, message)
    }

    func testTheMonthPageCoversWholeWeeksAndHintsWhileEmpty() async throws {
        let fixture = try makeFixture()
        let model = MonthModel(core: fixture.core)

        await model.load(model.currentMonth)

        let weeks = model.weeks(of: model.currentMonth)
        XCTAssertTrue((4...6).contains(weeks.count))
        XCTAssertTrue(weeks.allSatisfy { $0.count == 7 && $0.first?.weekday == .sunday })
        XCTAssertTrue(weeks.joined().contains { $0.day == fixture.today })
        XCTAssertTrue(model.showsFirstRunHint)

        _ = try fixture.core.addEvent(
            input: event(try personal(fixture.core), "치과", on: fixture.today), fromSuggestion: false)
        await model.reload()

        XCTAssertFalse(model.showsFirstRunHint)
        let cell = try XCTUnwrap(model.weeks(of: model.currentMonth).joined().first { $0.day == fixture.today })
        XCTAssertEqual(cell.events.map(\.title), ["치과"])
    }

    func testTheMonthPagesRunTenYearsEitherSide() throws {
        let model = MonthModel(core: try makeFixture().core)

        XCTAssertEqual(model.months.count, 241)
        XCTAssertEqual(model.months[120], model.currentMonth)
        model.show(month: model.months[0])
        model.showToday()
        XCTAssertEqual(model.visibleMonth, model.currentMonth)
    }

    func testTheWeekStartsOnSundayAndKnowsTheTime() async throws {
        let fixture = try makeFixture()
        let model = WeekModel(core: fixture.core)

        await model.load(model.currentWeek)

        let days = try XCTUnwrap(model.pages[model.currentWeek])
        XCTAssertEqual(days.count, 7)
        XCTAssertEqual(days.first?.weekday, .sunday)
        XCTAssertTrue(days.contains { $0.day == fixture.today })
        XCTAssertNotNil(model.nowMinute(on: model.currentWeek))
        XCTAssertNil(model.nowMinute(on: try XCTUnwrap(KoreanCalendar.adding(days: 7, to: model.currentWeek))))
    }

    func testTheListIsEmptyUntilSomethingIsPlannedAndGreysThePast() async throws {
        let fixture = try makeFixture()
        let model = AgendaModel(core: fixture.core)

        await model.reload()
        XCTAssertTrue(model.isEmpty)

        let calendar = try personal(fixture.core)
        let lastWeek = try XCTUnwrap(KoreanCalendar.adding(days: -7, to: fixture.today))
        _ = try fixture.core.addEvent(input: event(calendar, "지난 일", on: lastWeek), fromSuggestion: false)
        _ = try fixture.core.addEvent(input: event(calendar, "스터디", on: fixture.today), fromSuggestion: false)
        await model.reload()

        XCTAssertFalse(model.isEmpty)
        let past = try XCTUnwrap(model.days.first { $0.day == lastWeek })
        XCTAssertTrue(model.isPast(past))
        XCTAssertEqual(model.days.first { $0.day == fixture.today }?.events.map(\.title), ["스터디"])
    }

    func testTheQuickEntryBuildsATimedOrAllDayEvent() async throws {
        let fixture = try makeFixture()
        let model = QuickEntryModel(day: fixture.today, core: fixture.core)

        await model.load()

        XCTAssertEqual(model.selectedCalendar?.name, "개인")
        XCTAssertFalse(model.suggestions.isEmpty)
        XCTAssertNil(model.typedEvent())
        model.range = 18..<22
        XCTAssertTrue(model.needsTitle)

        model.title = " 스터디 "
        let timed = try XCTUnwrap(model.typedEvent())
        XCTAssertEqual(timed.title, "스터디")
        XCTAssertFalse(timed.allDay)
        XCTAssertEqual(timed.start, DayTime(day: fixture.today, minute: 540))
        XCTAssertEqual(timed.end, DayTime(day: fixture.today, minute: 660))

        model.range = 46..<48
        model.repeatChoice = .weekly
        let late = try XCTUnwrap(model.typedEvent())
        XCTAssertEqual(
            late.end, DayTime(day: try XCTUnwrap(KoreanCalendar.adding(days: 1, to: fixture.today)), minute: 0))
        XCTAssertEqual(late.recurrence?.frequency, .weekly)

        model.range = nil
        XCTAssertEqual(model.typedEvent()?.allDay, true)
    }

    func testAddingShowsTheToastAndUndoTakesTheEventBack() async throws {
        let fixture = try makeFixture()
        let toasts = ToastCenter()
        let actions = EventActions(core: fixture.core, toasts: toasts)
        let input = event(try personal(fixture.core), "PT", on: fixture.today)

        let added = await actions.add(input, fromSuggestion: false)

        XCTAssertTrue(added)
        XCTAssertEqual(toasts.current?.message, "PT를 추가했어요")
        XCTAssertEqual(toasts.current?.actionTitle, "되돌리기")
        XCTAssertNil(toasts.current?.proposal)
        toasts.performAction()
        try await waitForToast(toasts, "되돌렸어요")
        let agenda = try fixture.core.calendarAgenda(fromDay: fixture.today, days: 1)
        XCTAssertFalse(agenda.contains { !$0.events.isEmpty })
    }

    func testTheThirdSameTitleOnAWeekdayOffersToRepeatWeekly() async throws {
        let fixture = try makeFixture()
        let toasts = ToastCenter()
        let actions = EventActions(core: fixture.core, toasts: toasts)
        let calendar = try personal(fixture.core)
        for weeksBack in [14, 7] {
            let day = try XCTUnwrap(KoreanCalendar.adding(days: -weeksBack, to: fixture.today))
            _ = try fixture.core.addEvent(input: event(calendar, "카페 마감", on: day), fromSuggestion: false)
        }

        await actions.add(event(calendar, "카페 마감", on: fixture.today), fromSuggestion: false)

        let weekday = DayText.weekdayLongName(try XCTUnwrap(KoreanCalendar.weekday(of: fixture.today)))
        XCTAssertEqual(toasts.current?.message, "카페 마감을 추가했어요")
        XCTAssertEqual(toasts.current?.proposal?.message, "매주 \(weekday) 반복으로 바꿀까요?")
        toasts.acceptProposal()
        try await waitForToast(toasts, "매주 \(weekday)로 바꿨어요")
        let today = try XCTUnwrap(fixture.core.calendarAgenda(fromDay: fixture.today, days: 1).first)
        let event = try XCTUnwrap(today.events.first { $0.title == "카페 마감" })
        XCTAssertEqual(event.recurrence?.frequency, .weekly)
    }
}
