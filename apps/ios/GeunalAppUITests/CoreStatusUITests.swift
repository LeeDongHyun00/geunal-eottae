import XCTest

/// The M0 completion check (docs/parts/ios.md §10): the app opens the core, shows the SQLCipher version and finishes a
/// sync on the simulator. Debug builds keep the screen under the 나 tab.
final class CoreStatusUITests: GeunalUITestCase {
    func testShowsTheCoreOpenTheCipherVersionAndTheSyncResult() {
        // The real Rust core on a fresh install, straight to the tabs.
        let app = launch(realCore: true, ["-GeunalOnboardingFinished"])
        let me = app.tabBars.buttons["나"]
        waitFor(me)
        me.tap()
        let status = app.buttons["me.coreStatus"]
        waitFor(status)
        // The row is the last on 나, under the account rows.
        app.swipeUp()
        status.tap()

        let sync = app.staticTexts["status.sync"]
        XCTAssertTrue(sync.waitForExistence(timeout: 30))
        let finished = expectation(for: NSPredicate(format: "label == %@", "완료"), evaluatedWith: sync)
        wait(for: [finished], timeout: 60)

        XCTAssertEqual(app.staticTexts["status.open"].label, "완료")
        let cipher = app.staticTexts["status.cipher"].label
        XCTAssertTrue(cipher.first?.isNumber == true, "SQLCipher version, got '\(cipher)'")
    }
}
