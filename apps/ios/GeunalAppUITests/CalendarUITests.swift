import XCTest

/// The calendar tab on the real core: the month, the quick entry and its toast, the week and the list, one screenshot
/// each (docs/design/screens/m1 04–08), with the demo week (`-GeunalDemoCalendar`) in place. Both tests tap a toast,
/// so the toasts wait for them (`-GeunalToastsWait`): on a slow runner the undo was tapped six seconds after its toast
/// appeared, when the five-second toast was already leaving.
final class CalendarUITests: GeunalUITestCase {
    func testMonthQuickEntryWeekAndList() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished", "-GeunalDemoCalendar", "-GeunalToastsWait"])
        let today = app.buttons["month.day.\(koreanToday)"]
        waitFor(today)
        XCTAssertTrue(today.label.contains("오늘"), "today's cell reads as today, got '\(today.label)'")
        snapshot(app, "04-month")

        // Today's quick entry: the third 카페 마감 on this weekday comes as a chip and asks about repeating.
        tapDate(today)
        let chip = labeled(app.buttons, "카페 마감", prefix: true)
        waitFor(chip)
        snapshot(app, "05-quick-entry")
        chip.tap()
        let accept = app.buttons["toast.accept"]
        waitFor(accept)
        XCTAssertTrue(labeled(app.staticTexts, "카페 마감을 추가했어요").exists)
        snapshot(app, "06-added")
        accept.tap()
        waitFor(app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", "로 바꿨어요")).firstMatch)

        labeled(app.buttons, "주").tap()
        waitFor(app.buttons["week.day.\(koreanToday)"])
        snapshot(app, "07-week")

        labeled(app.buttons, "목록").tap()
        waitFor(labeled(app.buttons, "오늘 · ", prefix: true))
        snapshot(app, "08-list")
    }

    func testTypesATitleDragsATimeAndUndoes() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished", "-GeunalToastsWait"])
        let today = app.buttons["month.day.\(koreanToday)"]
        waitFor(today)
        tapDate(today)

        let save = app.buttons["quickEntry.save"]
        waitFor(save)
        XCTAssertFalse(save.isEnabled)
        // 09:00 to 11:00 on the 0–24 strip.
        let strip = app.descendants(matching: .any)["quickEntry.strip"]
        let from = strip.coordinate(withNormalizedOffset: CGVector(dx: 9.1 / 24, dy: 0.3))
        let to = strip.coordinate(withNormalizedOffset: CGVector(dx: 10.9 / 24, dy: 0.3))
        from.press(forDuration: 0.1, thenDragTo: to)
        waitUntil(save, "label == %@", "제목을 적어 주세요")

        let title = app.textFields["quickEntry.title"]
        title.tap()
        title.typeText("PT")
        waitUntil(save, "isEnabled == true")
        save.tap()

        waitFor(labeled(app.staticTexts, "PT를 추가했어요"))
        labeled(app.buttons, "되돌리기").tap()
        waitFor(labeled(app.staticTexts, "되돌렸어요"))
    }
}
