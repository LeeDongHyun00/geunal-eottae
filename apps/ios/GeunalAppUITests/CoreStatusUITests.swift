import XCTest

/// The M0 completion check (docs/parts/ios.md §10): the app opens the core, shows the SQLCipher version and finishes a
/// sync on the simulator.
final class CoreStatusUITests: XCTestCase {
    @MainActor
    func testShowsTheCoreOpenTheCipherVersionAndTheSyncResult() {
        let app = XCUIApplication()
        app.launch()

        let sync = app.staticTexts["status.sync"]
        XCTAssertTrue(sync.waitForExistence(timeout: 30))
        let finished = expectation(for: NSPredicate(format: "label == %@", "완료"), evaluatedWith: sync)
        wait(for: [finished], timeout: 60)

        XCTAssertEqual(app.staticTexts["status.open"].label, "완료")
        let cipher = app.staticTexts["status.cipher"].label
        XCTAssertTrue(cipher.first?.isNumber == true, "SQLCipher version, got '\(cipher)'")
    }
}
