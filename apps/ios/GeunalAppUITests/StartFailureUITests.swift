import XCTest

/// The start failure screen (m1.md §4 "시작 오류", decision 37): the kind of failure, the steps, and a retry that opens
/// the core again.
final class StartFailureUITests: GeunalUITestCase {
    func testOpensTheCoreAgainOnRetry() {
        let app = launch(["-GeunalFailFirstOpen"])
        let reason = app.staticTexts["start.failure"]
        waitFor(reason)
        XCTAssertEqual(reason.label, "저장소를 열지 못했어요")
        snapshot(app, "00-start-failure")

        app.buttons["start.retry"].tap()

        waitFor(app.buttons["welcome.start"])
    }
}
