import UIKit
import XCTest

/// The account flows after the core's sign-in and restore landed, on the preview core: "백업 가져오기" with "나중에
/// 넣을게요" (m1.md decision 24), the start-fresh confirmation, the checksum on the device, another account's events
/// after a new sign-in (screen 24) and the permissions screen without a way back (decision 30).
final class AccountFlowUITests: GeunalUITestCase {
    private let code = "1 바람 2 연필 3 구름 4 달력 5 열쇠 6 우산 7 단추 8 마당 9 편지 10 소풍 11 거울 12 하늘"

    private func signInWithKakao(_ app: XCUIApplication) {
        openSignIn(app)
        app.buttons["signIn.age"].tap()
        app.buttons["signIn.kakao"].tap()
    }

    func testTheOfferWaitsForTheCodeAndTheBannerAsksForItLater() {
        let app = launch(["-GeunalPreviewScenario", "offer"])
        signInWithKakao(app)
        waitFor(app.buttons["restore.noCode"])
        XCTAssertTrue(labeled(app.staticTexts, "백업 가져오기").exists)
        snapshot(app, "21-restore--offer")

        app.buttons["restore.noCode"].tap()
        let later = app.buttons["restore.later"]
        waitFor(later)
        snapshot(app, "21-restore--offer-no-code")
        later.tap()

        let finish = app.buttons["permissions.later"]
        waitFor(finish)
        // The account is decided: no way back to the sign-in (decision 30).
        XCTAssertFalse(app.navigationBars.buttons.element(boundBy: 0).exists)
        finish.tap()

        let banner = app.descendants(matching: .any)["month.recoveryBanner"]
        waitFor(banner)
        snapshot(app, "04-month--recovery-pending")
        labeled(app.buttons, "넣기").tap()
        waitFor(app.buttons["restore.close"])
        app.buttons["restore.close"].tap()
        waitFor(banner)
    }

    func testStartingFreshConfirmsTheBackupsItDeletes() {
        let app = launch(["-GeunalPreviewScenario", "replace"])
        signInWithKakao(app)
        waitFor(app.buttons["restore.noCode"])
        app.buttons["restore.noCode"].tap()
        app.buttons["restore.startOver"].tap()

        let confirm = app.buttons["restore.confirmFresh"]
        waitFor(confirm)
        waitUntil(confirm, "label == %@", "백업 1개 지우고 새로 시작")
        snapshot(app, "21-restore--confirm")

        app.buttons["restore.keepBackups"].tap()
        waitFor(app.buttons["restore.startOver"])
        app.buttons["restore.startOver"].tap()
        waitFor(confirm)
        confirm.tap()
        waitFor(app.buttons["permissions.later"])
    }

    func testTwoSwappedWordsFailTheChecksumOnTheDevice() {
        let app = launch(["-GeunalPreviewScenario", "replace"])
        signInWithKakao(app)
        let start = app.buttons["restore.start"]
        waitFor(start)

        UIPasteboard.general.string = code.replacingOccurrences(of: "1 바람 2 연필", with: "1 연필 2 바람")
        app.buttons["restore.paste"].tap()
        waitUntil(start, "label == %@", "코드를 다시 확인해 주세요")
        XCTAssertFalse(start.isEnabled)
        snapshot(app, "21-restore--error-checksum")
    }

    func testANewAccountAnswersForAnotherAccountsEventsFirst() {
        let app = launch(["-GeunalPreviewScenario", "otherAccountSignIn"])
        signInWithKakao(app)
        let keep = app.buttons["keepLocal.keep"]
        waitFor(keep)
        snapshot(app, "24-keep-local--sign-in")

        keep.tap()
        waitFor(app.buttons["permissions.later"])
        XCTAssertFalse(keep.exists)
    }
}
