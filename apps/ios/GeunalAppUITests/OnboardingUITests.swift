import XCTest

/// The first run on the preview core: welcome, sign-in with the age box, guest, permissions, then the tabs.
final class OnboardingUITests: GeunalUITestCase {
    func testWalksFromTheWelcomeScreenToTheTabs() {
        let app = launch()
        waitFor(app.buttons["welcome.start"])
        snapshot(app, "01-welcome")

        openSignIn(app)
        XCTAssertFalse(app.buttons["signIn.guest"].isEnabled)
        XCTAssertFalse(app.buttons["signIn.kakao"].isEnabled)
        XCTAssertTrue(app.buttons["signIn.guestReturn"].isEnabled)
        snapshot(app, "02-sign-in")
        app.buttons["signIn.age"].tap()
        waitUntil(app.buttons["signIn.guest"], "isEnabled == true")
        snapshot(app, "02-sign-in--checked")

        app.buttons["signIn.guest"].tap()
        let later = app.buttons["permissions.later"]
        waitFor(later)
        XCTAssertFalse(app.buttons["permissions.open"].isEnabled)
        snapshot(app, "03-permissions")
        app.buttons["permissions.alphaStats"].tap()
        waitUntil(app.buttons["permissions.alphaStats"], "label == %@", "보내요 · 나에서 끌 수 있어요")
        snapshot(app, "03-permissions--stats-on")

        later.tap()
        let me = app.tabBars.buttons["나"]
        waitFor(me)
        waitFor(app.descendants(matching: .any)["nextUp"])
        snapshot(app, "04-calendar-tab")
        me.tap()
        waitFor(app.descendants(matching: .any)["me.profile"])
        snapshot(app, "12-me")
    }
}
