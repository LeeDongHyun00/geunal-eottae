import CoreBridge
import FeatureCalendar
import GeunalCore
import XCTest

/// 캘린더 관리, 캘린더 편집 and 알림 on the real core in a fresh store (m1.md screens 15, 16, 19).
@MainActor
final class SettingsModelTests: XCTestCase {
    private func makeCore() throws -> Core {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try Core.open(
            storeDir: directory.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(), secrets: MemorySecretStore())
    }

    func testMakesACalendarAndListsItWithWhatItHolds() async throws {
        let core = try makeCore()
        let edit = CalendarEditModel(core: core)
        edit.name = "  운동  "
        edit.color = .sea
        edit.soft = true

        let id = await edit.save()
        let model = CalendarsModel(core: core)
        await model.load()

        let made = try XCTUnwrap(model.own.first { $0.id == id })
        XCTAssertEqual(made.name, "운동")
        XCTAssertEqual(made.color, .sea)
        XCTAssertTrue(made.soft)
        XCTAssertEqual(CalendarsModel.detail(made), "바다 · 소프트 캘린더, 그룹에는 비어 있음으로")
    }

    func testHidesACalendarAndKeepsTheLastOneFromDeletion() async throws {
        let core = try makeCore()
        let model = CalendarsModel(core: core)
        await model.load()
        let only = try XCTUnwrap(model.own.first)

        await model.setVisible(only.id, false)
        XCTAssertEqual(model.own.first?.visible, false)

        let edit = CalendarEditModel(calendar: only, isLast: model.own.count == 1, core: core)
        XCTAssertFalse(edit.canDelete)
        let deleted = await edit.delete()
        XCTAssertFalse(deleted)
    }

    func testANameIsNeededToSave() async throws {
        let edit = CalendarEditModel(core: try makeCore())
        edit.name = "   "

        XCTAssertFalse(edit.canSave)
        let id = await edit.save()
        XCTAssertNil(id)
    }

    func testSavesEachAlertSettingAtOnce() async throws {
        let core = try makeCore()
        let model = NotificationSettingsModel(core: core, permission: { .off })
        await model.load()
        XCTAssertTrue(model.systemOff)

        await model.setEventAlert(30)
        await model.setNextUpBar(false)
        await model.setAllDayAlert(nil)

        let stored = try core.notificationSettings()
        XCTAssertEqual(stored.eventAlert, 30)
        XCTAssertFalse(stored.nextUpBar)
        XCTAssertNil(stored.allDayAlert)
        XCTAssertEqual(model.settings, stored)
        XCTAssertFalse(model.failed)
    }

    func testDescribesTheAlertChoices() {
        XCTAssertEqual(NotificationSettingsModel.describe(10, allDay: false), "10분 전")
        XCTAssertEqual(NotificationSettingsModel.describe(-540, allDay: true), "당일 09:00")
        XCTAssertEqual(NotificationSettingsModel.describe(360, allDay: true), "전날 18:00")
        XCTAssertEqual(NotificationSettingsModel.describe(nil, allDay: false), "없음")
    }
}
