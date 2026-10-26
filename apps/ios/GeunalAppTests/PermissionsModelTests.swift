import FeatureAccount
import GeunalCore
import XCTest

@MainActor
final class PermissionsModelTests: XCTestCase {
    @MainActor
    private struct Fixture {
        let core = AccountFakeCore()
        let permissions = FakePermissions()
        let toasts = ToastLog()

        func model(_ channel: BuildChannel = .alpha) -> PermissionsModel {
            let toasts = self.toasts
            return PermissionsModel(
                core: core, permissions: permissions, channel: channel, notify: { toasts.append($0) })
        }
    }

    func testCountsTheDeviceCalendarsOnceAllowed() async {
        let f = Fixture()
        let model = f.model()

        await model.calendarTapped()

        XCTAssertEqual(model.calendar, .granted)
        XCTAssertEqual(model.calendarCount, 3)
    }

    func testOpensTheCalendarOnlyOnceBothAreAnswered() async {
        let f = Fixture()
        let model = f.model()
        await model.refresh()
        XCTAssertFalse(model.canOpenCalendar)

        await model.calendarTapped()
        await model.notificationsTapped()

        XCTAssertEqual(model.notifications, .denied)
        XCTAssertTrue(model.canOpenCalendar)
    }

    func testSendsARefusalToTheSettings() async {
        let f = Fixture()
        f.permissions.calendar = .denied
        let model = f.model()
        await model.refresh()

        await model.calendarTapped()

        XCTAssertEqual(f.permissions.openedSettings, 1)
    }

    func testShowsWhatThePersonTurnedOnInTheSettings() async {
        let f = Fixture()
        f.permissions.calendar = .denied
        f.permissions.notifications = .denied
        let model = f.model()
        await model.refresh()
        await model.calendarTapped()

        f.permissions.calendar = .granted
        f.permissions.notifications = .granted
        await model.refresh()

        XCTAssertEqual(model.calendar, .granted)
        XCTAssertEqual(model.calendarCount, 3)
        XCTAssertEqual(model.notifications, .granted)
    }

    func testTurnsTheStatisticsOnOnlyWhenAsked() async {
        let f = Fixture()
        let model = f.model()
        await model.refresh()
        XCTAssertFalse(model.alphaStats)

        model.alphaStatsTapped()

        XCTAssertTrue(model.alphaStats)
        XCTAssertEqual(f.core.calls.current, ["alphaStats true"])
    }

    func testHasNoStatisticsCardInAPublicBuild() {
        let f = Fixture()
        let model = f.model(.public)

        model.alphaStatsTapped()

        XCTAssertFalse(model.showsAlphaStats)
        XCTAssertEqual(f.core.calls.current, [])
    }

    func testSaysWhenTheStatisticsCouldNotBeTurnedOn() {
        let f = Fixture()
        f.core.alphaStatsError = .NotImplemented(what: "set_alpha_stats")
        let model = f.model()

        model.alphaStatsTapped()

        XCTAssertFalse(model.alphaStats)
        XCTAssertEqual(f.toasts.messages, ["설정을 바꾸지 못했어요. 다시 시도해 주세요"])
    }
}
