import XCTest

/// The editor, the shift stamp screen and the template sheet on the real core with the demo week, one screenshot each
/// (docs/design/screens/m1 09, 10, 18).
final class EditorUITests: GeunalUITestCase {
    func testEditsAnEventFromTheList() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished", "-GeunalDemoCalendar"])
        waitFor(app.buttons["month.day.\(koreanToday)"])
        labeled(app.buttons, "목록").tap()

        // Tomorrow's dinner, near the top of the list once it opens at today.
        let row = labeled(app.buttons, "가족 · 저녁", prefix: true)
        waitFor(row)
        row.tap()
        let title = app.textFields["editor.title"]
        waitFor(title)
        waitUntil(title, "value == %@", "가족 · 저녁")
        snapshot(app, "09-editor")

        title.tap()
        title.typeText(" 2")
        app.buttons["editor.save"].tap()
        waitFor(labeled(app.buttons, "가족 · 저녁 2", prefix: true))
    }

    func testStampsShiftsWithANewTemplate() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished"])
        waitFor(app.buttons["month.day.\(koreanToday)"])
        app.buttons["calendar.add"].tap()
        labeled(app.buttons, "교대 찍기").tap()

        let first = app.buttons["shift.firstTemplate"]
        waitFor(first)
        first.tap()
        let name = app.textFields["template.name"]
        waitFor(name)
        name.tap()
        name.typeText("Open")
        snapshot(app, "18-template-edit")
        app.buttons["template.save"].tap()

        let day = app.buttons["shift.day.\(koreanToday)"]
        waitFor(app.buttons["shift.template.Open"])
        day.tap()
        waitUntil(day, "label CONTAINS %@", "Open")
        snapshot(app, "10-shift")
        app.buttons["shift.done"].tap()

        waitFor(app.buttons["month.day.\(koreanToday)"])
        waitUntil(app.buttons["month.day.\(koreanToday)"], "label CONTAINS %@", "일정 1개")
    }
}
