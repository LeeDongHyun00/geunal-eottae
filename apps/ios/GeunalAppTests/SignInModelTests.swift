import FeatureAccount
import GeunalCore
import XCTest

@MainActor
final class SignInModelTests: XCTestCase {
    @MainActor
    private struct Fixture {
        let core = AccountFakeCore()
        let providers = FakeProviders()
        let state = OnboardingState(defaults: scratchDefaults())
        let toasts = ToastLog()

        func model() -> SignInModel {
            let toasts = self.toasts
            return SignInModel(
                core: core, providers: providers, state: state, now: { Date(timeIntervalSince1970: 1_793_000_000) },
                notify: { toasts.append($0) })
        }
    }

    func testDoesNothingUntilTheAgeBoxIsTicked() async {
        let f = Fixture()
        let model = f.model()

        let outcome = await model.signIn(with: .kakao, isOnline: true)

        XCTAssertNil(outcome)
        XCTAssertEqual(f.core.calls.current, [])
    }

    func testKeepsOnlyTheTimeTheBoxWasTicked() {
        let f = Fixture()
        let model = f.model()

        model.isAgeConfirmed = true
        XCTAssertEqual(model.ageConfirmedAt, Date(timeIntervalSince1970: 1_793_000_000))
        model.isAgeConfirmed = false
        XCTAssertNil(model.ageConfirmedAt)
    }

    func testHandsTheProviderNonceToKakaoAndTheNonceToTheCore() async {
        let f = Fixture()
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.signIn(with: .kakao, isOnline: true)

        XCTAssertEqual(outcome, .permissions)
        XCTAssertEqual(f.providers.nonces, ["p1"])
        XCTAssertEqual(f.core.calls.current, ["nonce", "kakao n1 token"])
        XCTAssertEqual(f.core.lastAccount.current?.displayName, "하린")
        XCTAssertEqual(f.core.lastAccount.current?.ageConfirmedAt, Date(timeIntervalSince1970: 1_793_000_000))
    }

    func testGoesToTheDeviceChangeWhenAnotherDeviceIsActive() async {
        let f = Fixture()
        f.core.authResult = .success(
            .replaceRequired(userId: "u1", activePlatform: "android", activeSince: Date(), recovery: true))
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.signIn(with: .apple, isOnline: true)

        XCTAssertEqual(outcome, .restore(.deviceChange))
    }

    func testOffersTheBackupOfAnExistingAccount() async {
        let f = Fixture()
        f.core.authResult = .success(
            .registered(session: Session(userId: "u1", deviceId: "d1"), newAccount: false, recovery: true))
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.signIn(with: .kakao, isOnline: true)

        XCTAssertEqual(outcome, .restore(.offer))
    }

    func testStaysQuietWhenThePersonCancels() async {
        let f = Fixture()
        f.providers.result = .failure(.cancelled)
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.signIn(with: .kakao, isOnline: true)

        XCTAssertNil(outcome)
        XCTAssertEqual(f.toasts.messages, [])
    }

    func testSaysTheSignInFailed() async {
        let f = Fixture()
        f.core.authResult = .failure(.Api(status: 401, code: "unauthenticated", detail: ""))
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.signIn(with: .kakao, isOnline: true)

        XCTAssertNil(outcome)
        XCTAssertEqual(f.toasts.messages, ["로그인이 안 됐어요. 다시 시도해 주세요"])
        XCTAssertNil(model.busy)
    }

    func testDoesNotCallAProviderOffline() async {
        let f = Fixture()
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.signIn(with: .apple, isOnline: false)

        XCTAssertNil(outcome)
        XCTAssertEqual(f.providers.nonces, [])
        XCTAssertEqual(f.toasts.messages, ["연결이 없어요. 게스트로 시작하고 나중에 연결할 수 있어요"])
    }

    func testMakesAGuestAccountWhenOnline() async {
        let f = Fixture()
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.continueAsGuest(isOnline: true)

        XCTAssertEqual(outcome, .permissions)
        XCTAssertEqual(f.core.lastAccount.current?.displayName, "게스트")
        XCTAssertNil(f.state.pendingGuestAgeConfirmedAt)
    }

    func testKeepsTheTickedTimeForAGuestWhoStartsOffline() async {
        let f = Fixture()
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.continueAsGuest(isOnline: false)

        XCTAssertEqual(outcome, .permissions)
        XCTAssertEqual(f.core.calls.current, [])
        XCTAssertEqual(f.state.pendingGuestAgeConfirmedAt, Date(timeIntervalSince1970: 1_793_000_000))
    }

    func testKeepsTheGuestPendingWhileTheCoreCannotMakeIt() async {
        let f = Fixture()
        f.core.guestResult = .failure(.NotImplemented(what: "sign_in_guest"))
        let model = f.model()
        model.isAgeConfirmed = true

        let outcome = await model.continueAsGuest(isOnline: true)

        XCTAssertEqual(outcome, .permissions)
        XCTAssertNotNil(f.state.pendingGuestAgeConfirmedAt)
    }

    func testCompletesAPendingGuestLater() async {
        let f = Fixture()
        f.state.pendingGuestAgeConfirmedAt = Date(timeIntervalSince1970: 1_793_000_000)

        await PendingGuest.complete(core: f.core, state: f.state)

        XCTAssertEqual(f.core.calls.current, ["guest"])
        XCTAssertNil(f.state.pendingGuestAgeConfirmedAt)
    }

    func testNamesAnAccountWithoutAProviderName() async {
        let f = Fixture()
        f.providers.result = .success(ProviderCredential(idToken: "token", displayName: "  "))
        let model = f.model()
        model.isAgeConfirmed = true

        _ = await model.signIn(with: .apple, isOnline: true)

        XCTAssertEqual(f.core.lastAccount.current?.displayName, "Apple 사용자")
    }

    func testCutsALongNameToForty() async {
        let f = Fixture()
        f.providers.result = .success(
            ProviderCredential(idToken: "token", displayName: String(repeating: "가", count: 50)))
        let model = f.model()
        model.isAgeConfirmed = true

        _ = await model.signIn(with: .kakao, isOnline: true)

        XCTAssertEqual(f.core.lastAccount.current?.displayName.count, 40)
    }
}
