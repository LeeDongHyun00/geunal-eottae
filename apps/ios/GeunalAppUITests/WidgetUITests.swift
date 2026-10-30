import XCTest

/// The widgets drawn from the snapshot the app writes for them, on the real core with the demo week (m1.md screen 14).
/// The home screen is out of a UI test's reach, so a debug-only gallery draws the widget views in the app.
final class WidgetUITests: GeunalUITestCase {
    func testDrawsTheWidgetsFromTheAppsSnapshot() {
        let app = launch(realCore: true, ["-GeunalOnboardingFinished", "-GeunalDemoCalendar", "-GeunalWidgetGallery"])
        waitFor(app.descendants(matching: .any)["widgetGallery"])
        // The demo week's dinner is tomorrow, on the medium widgets' right; an event reads as one element.
        waitFor(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS '가족 · 저녁'")).firstMatch)
        XCTAssertTrue(app.staticTexts["내일"].firstMatch.exists)
        snapshot(app, "14-widgets")
    }
}
