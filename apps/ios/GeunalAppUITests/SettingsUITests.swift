import XCTest

/// 캘린더 관리, 캘린더 편집 and 알림 on the real core with the demo week (docs/design/screens/m1 15, 16, 19).
final class SettingsUITests: GeunalUITestCase {
    func testListsTheCalendarsAndTurnsTheNextUpBarOff() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished", "-GeunalDemoCalendar"])
        let me = app.tabBars.buttons["나"]
        waitFor(me)
        waitFor(app.descendants(matching: .any)["nextUp"])
        me.tap()

        app.buttons["me.calendars"].tap()
        let school = app.buttons["calendars.row.학교"]
        waitFor(school)
        XCTAssertTrue(school.label.contains("바다"), "the row says its color, got '\(school.label)'")
        snapshot(app, "15-calendars")

        school.tap()
        let save = app.buttons["calendarEdit.save"]
        waitFor(save)
        snapshot(app, "16-calendar-edit")
        labeled(app.buttons, "닫기").tap()
        waitUntil(save, "exists == false")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.buttons["me.notifications"].tap()
        let nextUp = app.switches["notifications.nextUp"]
        waitFor(nextUp)
        snapshot(app, "19-notifications")
        // The switch sits at the row's right end; the middle of a SwiftUI toggle row is its label.
        nextUp.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        waitUntil(app.descendants(matching: .any)["nextUp"], "exists == false")
    }
}
