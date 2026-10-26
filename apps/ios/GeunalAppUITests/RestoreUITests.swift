import UIKit
import XCTest

/// The recovery-code screens on the preview core: the guest return and the device change, the paste, the questions
/// before a restore and "돌아왔어요".
final class RestoreUITests: GeunalUITestCase {
    private let code = "1 바람 2 연필 3 구름 4 달력 5 열쇠 6 우산 7 단추 8 마당 9 편지 10 소풍 11 거울 12 하늘"

    func testShowsTheGuestReturnAndItsNoCodeSheet() {
        let app = launch()
        openSignIn(app)

        app.buttons["signIn.guestReturn"].tap()
        waitFor(app.buttons["restore.noCode"])
        XCTAssertFalse(app.buttons["restore.start"].isEnabled)
        snapshot(app, "21-restore--guest")
        app.buttons["restore.noCode"].tap()
        waitFor(app.buttons["restore.startOver"])
        snapshot(app, "21-restore--guest-no-code")
    }

    func testChangesTheDeviceFromAPastedCode() {
        let app = launch(["-GeunalPreviewScenario", "otherAccountData"])
        openSignIn(app)
        app.buttons["signIn.age"].tap()
        app.buttons["signIn.kakao"].tap()
        let start = app.buttons["restore.start"]
        waitFor(start)
        snapshot(app, "21-restore")

        UIPasteboard.general.string = code
        app.buttons["restore.paste"].tap()
        waitUntil(start, "isEnabled == true")
        snapshot(app, "21-restore--ready")

        start.tap()
        waitFor(app.buttons["keepLocal.keep"])
        snapshot(app, "24-keep-local")
        app.buttons["keepLocal.keep"].tap()
        waitFor(app.buttons["restore.open"])
        snapshot(app, "21-restore--done")
        app.buttons["restore.open"].tap()
        waitFor(app.tabBars.buttons["캘린더"])
    }

    func testMarksAWordThatIsNotInTheList() {
        let app = launch(["-GeunalPreviewScenario", "replace"])
        openSignIn(app)
        app.buttons["signIn.age"].tap()
        app.buttons["signIn.apple"].tap()
        waitFor(app.buttons["restore.start"])

        UIPasteboard.general.string = code.replacingOccurrences(of: "마당", with: "바다")
        app.buttons["restore.paste"].tap()
        waitUntil(app.buttons["restore.start"], "label == %@", "8번째 단어를 고쳐 주세요")
        snapshot(app, "21-restore--error-word")
    }
}
