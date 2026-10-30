import XCTest

/// 나 and the account screens on the preview core (docs/design/screens/m1 12, 13, 20, 22, 23), whose OS prompt
/// stand-in passes at once. One screenshot each.
final class MeUITests: GeunalUITestCase {
    private func openMe(_ app: XCUIApplication) {
        let me = app.tabBars.buttons["나"]
        waitFor(me)
        me.tap()
        waitFor(app.descendants(matching: .any)["me.profile"])
    }

    func testAGuestWritesTheCodeDownFromTheMonthBanner() {
        let app = launch(["-GeunalOnboardingFinished", "-GeunalPreviewScenario", "guest"])
        let banner = app.descendants(matching: .any)["month.recoveryBanner"]
        waitFor(banner)
        snapshot(app, "04-month--recovery-banner")

        labeled(app.buttons, "저장").tap()
        let saved = app.buttons["recovery.saved"]
        waitFor(saved)
        waitFor(app.descendants(matching: .any)["recovery.words"])
        XCTAssertFalse(app.descendants(matching: .any)["recovery.noScreenLock"].exists)
        snapshot(app, "13-recovery")

        saved.tap()
        waitUntil(app.buttons["me.recovery"], "label CONTAINS %@", "저장함")
        snapshot(app, "12-me--guest")
        app.tabBars.buttons["캘린더"].tap()
        waitFor(app.buttons["month.day.\(koreanToday)"])
        waitUntil(banner, "exists == false")
    }

    func testADeviceWithoutAScreenLockSaysALockWouldHelp() {
        let app = launch(["-GeunalOnboardingFinished", "-GeunalPreviewScenario", "guest", "-GeunalNoScreenLock"])
        openMe(app)
        app.buttons["me.recovery"].tap()
        waitFor(app.descendants(matching: .any)["recovery.noScreenLock"])
        snapshot(app, "13-recovery--no-screen-lock")
    }

    func testAKakaoAccountShowsItsBackupAndTheDeletionSheet() {
        let app = launch(["-GeunalOnboardingFinished", "-GeunalPreviewScenario", "kakao"])
        openMe(app)
        XCTAssertFalse(app.buttons["me.link"].exists)
        snapshot(app, "12-me--account")

        app.buttons["me.backup"].tap()
        waitFor(app.buttons["backup.now"])
        snapshot(app, "20-backup")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.swipeUp()
        let delete = app.buttons["me.delete"]
        waitFor(delete)
        delete.tap()
        let start = app.buttons["delete.start"]
        waitFor(start)
        snapshot(app, "22-delete-account")

        start.tap()
        let confirm = app.buttons["delete.confirm"]
        waitFor(confirm)
        XCTAssertFalse(confirm.isEnabled)
        XCTAssertEqual(confirm.label, "‘삭제’를 적으면 카카오로 확인해요")
        snapshot(app, "22-delete-account--confirm")
    }

    func testAGuestDeletesTheAccountAndLandsOnTheWelcomeScreen() {
        let app = launch(["-GeunalOnboardingFinished", "-GeunalPreviewScenario", "guest"])
        openMe(app)
        app.swipeUp()
        app.buttons["me.delete"].tap()
        app.buttons["delete.start"].tap()
        let word = app.textFields["delete.word"]
        waitFor(word)
        word.tap()
        word.typeText("삭제")
        let confirm = app.buttons["delete.confirm"]
        waitUntil(confirm, "isEnabled == true")
        snapshot(app, "22-delete-account--guest")

        confirm.tap()
        waitFor(app.buttons["welcome.start"])
        waitFor(labeled(app.staticTexts, "계정을 삭제했어요"))
    }

    func testAnotherDevicesSignInShowsTheSheetOnceThenTheSignedOutCard() {
        let app = launch(["-GeunalOnboardingFinished", "-GeunalPreviewScenario", "revoked"])
        let calendarOnly = app.buttons["revoked.calendarOnly"]
        waitFor(calendarOnly)
        snapshot(app, "23-revoked--recovery")

        calendarOnly.tap()
        openMe(app)
        let signIn = app.buttons["me.signIn"]
        waitFor(signIn)
        snapshot(app, "12-me--signed-out")

        signIn.tap()
        let close = app.buttons["onboarding.close"]
        waitFor(close)
        waitFor(app.buttons["signIn.age"])
        close.tap()
        waitFor(signIn)
        XCTAssertFalse(calendarOnly.exists)
    }
}
