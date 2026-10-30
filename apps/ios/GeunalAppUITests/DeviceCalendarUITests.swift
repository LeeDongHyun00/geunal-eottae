import XCTest

/// The device calendar mirror on the real core, with a made-up phone in for EventKit (m1.md screen 15's second
/// group): the switch imports every calendar, a checkbox takes one out, and 나 says the import is on.
final class DeviceCalendarUITests: GeunalUITestCase {
    func testImportsThePhonesCalendarsAndTakesOneOut() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished", "-GeunalDemoCalendar", "-GeunalPhoneCalendars"])
        let me = app.tabBars.buttons["나"]
        waitFor(me)
        me.tap()

        let row = app.buttons["me.deviceCalendars"]
        waitFor(row)
        XCTAssertTrue(row.label.contains("꺼짐"), "the import starts off, got '\(row.label)'")
        row.tap()

        let toggle = app.switches["calendars.device.import"]
        waitFor(toggle)
        app.swipeUp()
        toggle.tap()
        let family = app.buttons["calendars.device.iCloud · 가족"]
        waitFor(family)
        waitUntil(family, "isSelected == true")
        XCTAssertEqual(toggle.value as? String, "1")

        family.tap()
        waitUntil(family, "isSelected == false")
        XCTAssertTrue(app.buttons["calendars.device.Google · 회사"].isSelected)
        snapshot(app, "15-calendars-device")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        waitFor(row)
        waitUntil(row, "label CONTAINS '켜짐'")
    }
}
