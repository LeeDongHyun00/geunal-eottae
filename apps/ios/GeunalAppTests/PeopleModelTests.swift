import CoreBridge
import FeatureCalendar
import GeunalCore
import XCTest

/// Contacts the test decides on, and what the import asked of them.
@MainActor
final class FakeContactBook: ContactBook {
    var current: ContactAccess = .notAsked
    var answer: ContactAccess = .granted
    var candidates: [ContactCandidate] = []
    var withoutBirthday = 0
    private(set) var requests = 0
    private(set) var openedSettings = 0

    func access() -> ContactAccess { current }

    func requestAccess() async -> ContactAccess {
        requests += 1
        current = answer
        return answer
    }

    func birthdays() async -> (candidates: [ContactCandidate], withoutBirthday: Int) {
        (candidates, withoutBirthday)
    }

    func openSettings() {
        openedSettings += 1
    }
}

/// 사람·생일, the person sheet and the contacts import on the real core in a fresh store. The core reads today from
/// the clock, so birthdays are placed relative to today.
@MainActor
final class PeopleModelTests: XCTestCase {
    private func makeCore() throws -> Core {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try Core.open(
            storeDir: directory.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(), secrets: MemorySecretStore())
    }

    private var today: String { KoreanCalendar.day(of: Date()) }

    private func solar(in days: Int) -> Birthday {
        let day = KoreanCalendar.parse(KoreanCalendar.adding(days: days, to: today) ?? today)!
        return Birthday(month: UInt8(day.month), day: UInt8(day.day), year: nil, calendar: .solar)
    }

    @discardableResult
    private func add(_ core: Core, _ name: String, _ birthday: Birthday?) throws -> String {
        try core.savePerson(input: PersonInput(id: nil, name: name, birthday: birthday, alert: .inherit, suggest: true))
    }

    // 2026-10-26 in Seoul, the boards' year: 음력 10월 3일 is 11월 11일 수요일 this year.
    private let boardDay = Date(timeIntervalSince1970: 1_792_992_000)

    func testListsEveryoneAndTheBirthdaysOfTheNextThirtyDays() async throws {
        let core = try makeCore()
        try add(core, "민수", solar(in: 5))
        try add(core, "가영", solar(in: 60))
        try add(core, "도윤", nil)
        let model = PeopleModel(core: core)
        XCTAssertFalse(model.isEmpty)

        await model.load()

        XCTAssertEqual(model.people.map(\.name), ["가영", "도윤", "민수"])
        XCTAssertEqual(model.upcoming.map(\.name), ["민수"])
        let minsu = try XCTUnwrap(model.upcoming.first)
        XCTAssertEqual(minsu.daysUntil, 5)
        XCTAssertEqual(minsu.source, .manual)
        let day = try XCTUnwrap(KoreanCalendar.adding(days: 5, to: today))
        XCTAssertEqual(PeopleText.next(minsu, today: model.today), DayText.title(day, today: today))
        XCTAssertEqual(PeopleText.dDay(5), "D-5")
    }

    func testSearchesBothListsByAnyPartOfTheName() async throws {
        let core = try makeCore()
        try add(core, "민수", solar(in: 3))
        try add(core, "김민지", solar(in: 90))
        try add(core, "서연", solar(in: 4))
        let model = PeopleModel(core: core)
        await model.load()

        model.query = "민"

        XCTAssertEqual(model.matchingPeople.map(\.name), ["김민지", "민수"])
        XCTAssertEqual(model.matchingUpcoming.map(\.name), ["민수"])
        model.query = " "
        XCTAssertEqual(model.matchingPeople.count, 3)
    }

    func testIsEmptyOnceTheCoreHasNobody() async throws {
        let model = PeopleModel(core: try makeCore())

        await model.load()

        XCTAssertTrue(model.isEmpty)
    }

    func testSavesALunarBirthdayAndSaysWhereItFallsThisYear() async throws {
        let core = try makeCore()
        let model = PersonEditModel(core: core, now: { [boardDay] in boardDay })
        XCTAssertFalse(model.canSave)
        model.name = " 엄마 "
        model.setKind(.lunar)
        model.setMonth(10)
        model.setDay(3)

        await model.refreshNote()

        XCTAssertEqual(model.solarThisYear, "2026-11-11")
        XCTAssertEqual(
            PeopleText.lunarNote(solarThisYear: model.solarThisYear), "매년 양력으로 바꿔서 넣어요. 올해는 11월 11일 수요일이에요.")
        let id = await model.save()
        XCTAssertNotNil(id)
        let saved = try XCTUnwrap(try core.people().first)
        XCTAssertEqual(saved.name, "엄마")
        XCTAssertEqual(saved.birthday, Birthday(month: 10, day: 3, year: nil, calendar: .lunar(leapMonth: false)))
        XCTAssertEqual(PeopleText.birthday(try XCTUnwrap(saved.birthday)), "음력 10월 3일")
    }

    func testSaysThisSolarYearsDayForABirthdayLateInTheLunarYear() async throws {
        let core = try makeCore()
        let model = PersonEditModel(core: core, now: { [boardDay] in boardDay })
        model.setKind(.lunar)
        model.setMonth(12)
        model.setDay(3)

        await model.refreshNote()

        // Lunar 2026's twelfth month runs into January 2027; this solar year's 12/3 is lunar 2025's.
        XCTAssertEqual(try core.birthdayInYear(birthday: try XCTUnwrap(model.birthday), year: 2026), "2027-01-10")
        XCTAssertEqual(model.solarThisYear, "2026-01-21")
    }

    func testPicksTheComingDayWhenALunarBirthdayFallsTwiceInTheSolarYear() {
        XCTAssertEqual(
            PersonEditModel.solarDay(in: 2026, of: ["2026-01-02", "2026-12-22"], today: "2026-10-26"), "2026-12-22")
        XCTAssertEqual(
            PersonEditModel.solarDay(in: 2026, of: ["2026-01-02", "2027-01-10"], today: "2026-10-26"), "2026-01-02")
        XCTAssertNil(PersonEditModel.solarDay(in: 2026, of: ["2027-01-10"], today: "2026-10-26"))
    }

    func testTellsANewDayAndEachFocusRequestApart() {
        final class Clock: @unchecked Sendable {
            var now = Date(timeIntervalSince1970: 1_793_011_200)  // 2026-10-26 19:40 in Korea
        }
        let clock = Clock()
        let model = PeopleModel(core: FakeCore(), now: { clock.now })
        let day = model.today
        XCTAssertFalse(model.isNewDay(since: day))
        clock.now += 10 * 3600
        XCTAssertTrue(model.isNewDay(since: day))

        XCTAssertNotEqual(PersonFocus(personId: "p1"), PersonFocus(personId: "p1"))
    }

    func testFitsTheDayToTheMonthAndKeepsTheTwentyNinthOfFebruary() throws {
        let model = PersonEditModel(core: try makeCore())

        model.setMonth(1)
        model.setDay(31)
        model.setMonth(4)
        XCTAssertEqual(model.day, 30)
        model.setMonth(2)
        XCTAssertEqual(model.day, 29)
        model.setKind(.lunar)
        model.setMonth(1)
        model.setDay(31)
        XCTAssertEqual(model.day, 30)
        XCTAssertEqual(model.years.first, model.thisYear)
        XCTAssertEqual(model.years.last, 1900)
    }

    func testChangesTheAlertAndDeletesAPerson() async throws {
        let core = try makeCore()
        let id = try add(core, "서연", solar(in: 25))
        let person = try XCTUnwrap(try core.people().first)
        let model = PersonEditModel(person: person, core: core)
        XCTAssertFalse(model.isNew)
        XCTAssertEqual(model.name, "서연")
        XCTAssertNotNil(model.birthday)

        model.alert = .before(minutes: 360)
        let saved = await model.save()
        XCTAssertEqual(saved, id)
        XCTAssertEqual(try core.people().first?.alert, .before(minutes: 360))
        XCTAssertEqual(BirthdayAlertText.describe(.before(minutes: 360)), "전날 18:00")
        XCTAssertEqual(BirthdayAlertText.describe(.inherit), "당일 09:00")
        XCTAssertEqual(BirthdayAlertText.describe(.before(minutes: 9540)), "7일 전 09:00")

        let deleted = await model.delete()
        XCTAssertTrue(deleted)
        XCTAssertEqual(try core.people(), [])
    }

    func testAsksOnceAndBringsInOnlyThePickedContacts() async throws {
        let core = try makeCore()
        let contacts = FakeContactBook()
        let lunar = Birthday(month: 8, day: 20, year: nil, calendar: .lunar(leapMonth: false))
        contacts.candidates = [
            ContactCandidate(id: "c1", name: "할머니", birthday: lunar),
            ContactCandidate(id: "c2", name: "도윤", birthday: Birthday(month: 6, day: 2, year: 1998, calendar: .solar)),
            ContactCandidate(id: "c3", name: "가영", birthday: Birthday(month: 3, day: 14, year: nil, calendar: .solar)),
        ]
        contacts.withoutBirthday = 4
        let model = ContactImportModel(core: core, contacts: contacts)

        await model.start()

        XCTAssertEqual(contacts.requests, 1)
        XCTAssertEqual(model.phase, .picking)
        XCTAssertEqual(model.withoutBirthday, 4)
        XCTAssertTrue(model.selected.isEmpty)
        await model.importSelected()
        XCTAssertEqual(model.phase, .picking)
        model.toggle("c1")
        model.toggle("c2")
        model.toggle("c3")
        model.toggle("c3")
        await model.importSelected()

        guard case .finished(let result) = model.phase else { return XCTFail("phase is \(model.phase)") }
        XCTAssertEqual(result.added, 2)
        XCTAssertEqual(ContactImportModel.summary(result), "2명을 가져왔어요")
        let people = try core.people()
        XCTAssertEqual(people.map(\.name), ["도윤", "할머니"])
        XCTAssertEqual(people.map(\.source), [.contacts, .contacts])
    }

    func testImportingAgainUpdatesInsteadOfAddingACopy() async throws {
        let core = try makeCore()
        let contacts = FakeContactBook()
        contacts.current = .granted
        contacts.candidates = [
            ContactCandidate(id: "c1", name: "도윤", birthday: Birthday(month: 6, day: 2, year: nil, calendar: .solar))
        ]
        let first = ContactImportModel(core: core, contacts: contacts)
        await first.start()
        first.toggleAll()
        await first.importSelected()

        contacts.candidates = [
            ContactCandidate(id: "c1", name: "김도윤", birthday: Birthday(month: 6, day: 3, year: nil, calendar: .solar))
        ]
        let second = ContactImportModel(core: core, contacts: contacts)
        await second.start()
        XCTAssertFalse(second.allSelected)
        second.toggleAll()
        XCTAssertTrue(second.allSelected)
        await second.importSelected()

        XCTAssertEqual(contacts.requests, 0)
        guard case .finished(let result) = second.phase else { return XCTFail("phase is \(second.phase)") }
        XCTAssertEqual(result.added, 0)
        XCTAssertEqual(result.updated, 1)
        XCTAssertEqual(ContactImportModel.summary(result), "1명의 생일을 고쳤어요")
        XCTAssertEqual(try core.people().map(\.name), ["김도윤"])
    }

    func testSendsARefusalToTheSettingsAndReadsOnceAllowed() async throws {
        let contacts = FakeContactBook()
        contacts.answer = .denied
        let model = ContactImportModel(core: try makeCore(), contacts: contacts)

        await model.start()
        XCTAssertEqual(model.phase, .denied)
        model.openSettings()
        XCTAssertEqual(contacts.openedSettings, 1)

        // Back from the settings with the permission on.
        contacts.current = .granted
        await model.start()
        XCTAssertEqual(model.phase, .picking)
        XCTAssertEqual(contacts.requests, 1)
    }

    func testReadsBirthdaysTheHouseWay() {
        XCTAssertEqual(PeopleText.birthday(Birthday(month: 3, day: 14, year: 1995, calendar: .solar)), "3월 14일")
        XCTAssertEqual(
            PeopleText.birthday(Birthday(month: 4, day: 3, year: nil, calendar: .lunar(leapMonth: true))), "음력 윤4월 3일")
        XCTAssertEqual(PeopleText.dDay(0), "오늘")
        XCTAssertEqual(PeopleText.year(1995), "1995년")
        XCTAssertEqual(PeopleText.initial(" 엄마"), "엄")
        XCTAssertEqual(PeopleText.source(.contacts), "연락처에서")
        XCTAssertEqual(PeopleText.solarDay("2026-11-11", today: "2026-10-26"), "올해는 11월 11일 수요일")
        XCTAssertEqual(PeopleText.solarDay("2027-01-09", today: "2026-12-20"), "내년은 1월 9일 토요일")
        XCTAssertEqual(
            ContactImportModel.summary(ContactImport(added: 1, updated: 2, skipped: 0)), "1명을 새로 가져오고 2명을 고쳤어요")
    }
}
