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

    /// Welcome → sign-in.
    func openSignIn(_ app: XCUIApplication) {
        let start = app.buttons["welcome.start"]
        waitFor(start)
        start.tap()
        waitFor(app.buttons["signIn.age"])
    }
}
