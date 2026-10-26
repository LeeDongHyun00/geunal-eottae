import XCTest

/// Launch helpers and the screenshot attachments the ios workflow exports, named like docs/design/screens/m1.
@MainActor
class GeunalUITestCase: XCTestCase {
    /// A fresh install on the preview core unless `realCore`.
    func launch(realCore: Bool = false, _ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-GeunalResetState"] + (realCore ? [] : ["-GeunalPreviewCore"]) + extra
        app.launch()
        return app
    }

    func snapshot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func waitFor(_ element: XCUIElement, timeout: TimeInterval = 15, file: StaticString = #filePath, line: UInt = #line)
    {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "\(element) did not appear", file: file, line: line)
    }

    func waitUntil(_ element: XCUIElement, _ format: String, _ args: CVarArg..., timeout: TimeInterval = 15) {
        let done = expectation(for: NSPredicate(format: format, argumentArray: args), evaluatedWith: element)
        wait(for: [done], timeout: timeout)
    }

    /// Today in Korean time as the core writes days, for the identifiers of day cells ("month.day.2026-10-26").
    var koreanToday: String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul") ?? .current
        let parts = calendar.dateComponents([.year, .month, .day], from: Date())
        return String(format: "%04ld-%02ld-%02ld", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// The first element of `query` whose label is `label`, or starts with it.
    func labeled(_ query: XCUIElementQuery, _ label: String, prefix: Bool = false) -> XCUIElement {
        query.matching(NSPredicate(format: prefix ? "label BEGINSWITH %@" : "label == %@", label)).firstMatch
    }

    /// Taps a month cell on its date, above the event bars that open the editor.
    func tapDate(_ cell: XCUIElement) {
        cell.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
    }

    /// Welcome → sign-in.
    func openSignIn(_ app: XCUIApplication) {
        let start = app.buttons["welcome.start"]
        waitFor(start)
        start.tap()
        waitFor(app.buttons["signIn.age"])
    }
}
