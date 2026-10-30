import FeatureAccount
import GeunalCore
import XCTest

/// 나 and the account screens on a fake core: the card, the recovery code, the backup, linking and deleting the
/// account behind the OS prompt (m1.md screens 12, 13, 20, 22, decision 26).
@MainActor
final class MeModelTests: XCTestCase {
    private let kakao = AccountView(userId: "u1", displayName: "하린", providers: [.kakao])

    // MARK: 나

    func testTellsAGuestFromAProviderAccountAndNoAccount() async {
        let core = AccountFakeCore()
        let model = MeModel(core: core, channel: .public)

        await model.load()
        XCTAssertEqual(model.account, .guest(AccountView(userId: "u1", displayName: "하린", providers: [])))
        XCTAssertTrue(model.hasAccount)
        XCTAssertTrue(model.isGuest)
        XCTAssertEqual(model.summary?.people, 12)

        core.summary.account = kakao
        await model.load()
        XCTAssertEqual(model.account, .provider(kakao))
        XCTAssertFalse(model.isGuest)

        core.summary.account = nil
        await model.load()
        XCTAssertEqual(model.account, MeModel.Account.none)
        XCTAssertFalse(model.hasAccount)
    }

    func testIsSignedOutOnceAnotherDeviceSignedIn() async {
        let core = AccountFakeCore()
        let revocation = Revocation(at: Date(), byPlatform: "android", via: .recovery)
        core.revoked = revocation
        let model = MeModel(core: core, channel: .public)

        await model.load()

        XCTAssertEqual(model.account, .signedOut(revocation))
        XCTAssertFalse(model.hasAccount)
    }

    func testShowsTheAlphaSwitchOnlyInAlphaBuilds() async {
        let core = AccountFakeCore()
        core.alphaStats = true

        let publicBuild = MeModel(core: core, channel: .public)
        await publicBuild.load()
        XCTAssertFalse(publicBuild.showsAlphaStats)
        XCTAssertFalse(publicBuild.alphaStats)

        let alpha = MeModel(core: core, channel: .alpha)
        await alpha.load()
        XCTAssertTrue(alpha.showsAlphaStats)
        XCTAssertTrue(alpha.alphaStats)
        await alpha.setAlphaStats(false)
        XCTAssertEqual(core.calls.current, ["alphaStats false"])
    }

    // MARK: 복구 코드

    func testShowsTheTwelveWordsAndCopiesThemWithNumbers() async {
        let core = AccountFakeCore()
        let model = RecoveryCodeModel(core: core)

        await model.load()

        XCTAssertEqual(model.phase, .shown)
        XCTAssertEqual(model.words.count, 12)
        XCTAssertFalse(model.saved)
        XCTAssertEqual(
            model.copyText, "1. 바람 2. 연필 3. 구름 4. 달력 5. 열쇠 6. 우산 7. 단추 8. 마당 9. 편지 10. 소풍 11. 거울 12. 하늘")
    }

    func testHasNoWordsBeforeTheCodeIsMade() async {
        let core = AccountFakeCore()
        core.code = nil
        let model = RecoveryCodeModel(core: core)

        await model.load()

        XCTAssertEqual(model.phase, RecoveryCodeModel.Phase.none)
        XCTAssertEqual(model.words, [])
    }

    func testMarksTheCodeAsWrittenDown() async {
        let core = AccountFakeCore()
        let model = RecoveryCodeModel(core: core)
        await model.load()

        let saved = await model.markSaved()

        XCTAssertTrue(saved)
        XCTAssertTrue(model.saved)
        XCTAssertEqual(core.calls.current, ["markSaved"])
    }

    // MARK: 암호화 백업

    func testWaitsForTheRecoveryMaterialBeforeABackup() async {
        let core = AccountFakeCore()
        core.backupState.ready = false
        let model = BackupModel(core: core)
        await model.load()

        XCTAssertFalse(model.canBackUp)
        await model.backUpNow()

        XCTAssertEqual(core.calls.current, [])
    }

    func testSaysABackupNeedsTheCodeUntilItIsOnPaper() async {
        let core = AccountFakeCore()
        let model = BackupModel(core: core)

        await model.load()
        XCTAssertFalse(model.recoverySaved)

        core.recovery.saved = true
        await model.load()
        XCTAssertTrue(model.recoverySaved)
    }

    func testBacksUpNowAndSaysSo() async {
        let core = AccountFakeCore()
        let model = BackupModel(core: core)
        await model.load()

        await model.backUpNow()

        XCTAssertEqual(core.calls.current, ["backupNow"])
        XCTAssertEqual(model.phase, .idle)
        XCTAssertTrue(model.justMade)
    }

    func testABackupThatLostTheNetworkWaitsForIt() async {
        let core = AccountFakeCore()
        core.backupResult = .failure(.Network(reason: "offline"))
        let model = BackupModel(core: core)
        await model.load()

        await model.backUpNow()

        XCTAssertEqual(model.phase, .waitingForNetwork)
        XCTAssertFalse(model.justMade)
    }

    // MARK: 카카오·Apple 연결

    func testLinksAGuestToKakao() async {
        let core = AccountFakeCore()
        let toasts = ToastLog()
        let model = LinkModel(core: core, providers: FakeProviders(), notify: { toasts.append($0) })

        let linked = await model.link(.kakao, online: true)

        XCTAssertTrue(linked)
        XCTAssertEqual(model.linked, [.kakao])
        XCTAssertEqual(core.calls.current, ["nonce", "linkKakao n1 token"])
        XCTAssertEqual(toasts.messages, ["연결했어요"])
    }

    func testSaysWhenTheProviderAccountIsAnotherAccounts() async {
        let core = AccountFakeCore()
        let toasts = ToastLog()
        let model = LinkModel(core: core, providers: FakeProviders(), notify: { toasts.append($0) })

        core.linkResult = .failure(.Api(status: 409, code: "already_linked", detail: ""))
        _ = await model.link(.kakao, online: true)
        core.linkResult = .failure(.Api(status: 409, code: "account_exists", detail: ""))
        _ = await model.link(.apple, online: true)

        XCTAssertEqual(toasts.messages, ["이미 연결한 계정이에요", "이미 다른 계정에 연결된 카카오·Apple 계정이에요"])
    }

    func testLinkingWaitsForAConnectionAndIgnoresAClosedSheet() async {
        let core = AccountFakeCore()
        let providers = FakeProviders()
        providers.result = .failure(.cancelled)
        let toasts = ToastLog()
        let model = LinkModel(core: core, providers: providers, notify: { toasts.append($0) })

        let offline = await model.link(.kakao, online: false)
        let cancelled = await model.link(.kakao, online: true)

        XCTAssertFalse(offline)
        XCTAssertFalse(cancelled)
        XCTAssertEqual(toasts.messages, ["연결되면 할 수 있어요"])
        XCTAssertEqual(core.calls.current, ["nonce"])
    }

    // MARK: 계정 삭제

    private func deletion(
        _ core: AccountFakeCore, _ owner: FakeDeviceOwner, account: AccountView? = nil,
        providers: FakeProviders = FakeProviders()
    ) -> DeleteAccountModel {
        let model = DeleteAccountModel(
            core: core, providers: providers, owner: owner,
            account: account ?? AccountView(userId: "u1", displayName: "하린", providers: []))
        model.typed = "삭제"
        return model
    }

    func testWantsTheWordTypedOut() {
        let model = deletion(AccountFakeCore(), FakeDeviceOwner())

        model.typed = "삭"
        XCTAssertFalse(model.canConfirm)
        model.typed = " 삭제 "
        XCTAssertTrue(model.canConfirm)
        XCTAssertEqual(model.displayName, "하린")
    }

    func testAGuestPassesTheOwnerPromptAndSignsWithTheRecoveryKey() async {
        let core = AccountFakeCore()
        let owner = FakeDeviceOwner()
        let model = deletion(core, owner)

        let deleted = await model.delete(online: true)

        XCTAssertTrue(deleted)
        XCTAssertEqual(owner.asked, [.deleteAccount])
        XCTAssertEqual(core.lastProof.current, .guest)
        XCTAssertNil(model.problem)
    }

    func testCancellingTheOwnerPromptKeepsEverything() async {
        let core = AccountFakeCore()
        let owner = FakeDeviceOwner()
        owner.passes = false
        let model = deletion(core, owner)

        let deleted = await model.delete(online: true)

        XCTAssertFalse(deleted)
        XCTAssertEqual(core.calls.current, [])
        XCTAssertNil(model.problem)
        XCTAssertEqual(model.typed, "삭제")
        XCTAssertTrue(model.canConfirm)
    }

    func testADeviceWithoutAScreenLockDeletesAndRecommendsOne() async {
        let core = AccountFakeCore()
        let owner = FakeDeviceOwner()
        owner.hasScreenLock = false
        let model = deletion(core, owner)

        XCTAssertFalse(model.screenLocked)
        let deleted = await model.delete(online: true)

        XCTAssertTrue(deleted)
    }

    func testAKakaoAccountSignsInWithKakaoOnceMore() async {
        let core = AccountFakeCore()
        let owner = FakeDeviceOwner()
        let providers = FakeProviders()
        let model = deletion(core, owner, account: kakao, providers: providers)

        XCTAssertEqual(model.provider, .kakao)
        let deleted = await model.delete(online: true)

        XCTAssertTrue(deleted)
        XCTAssertEqual(providers.nonces, ["p1"])
        XCTAssertEqual(core.lastProof.current, .kakao(nonce: "n1", idToken: "token"))
    }

    func testAnotherKakaoAccountAsksAgainWithoutAnotherOwnerPrompt() async {
        let core = AccountFakeCore()
        core.deleteErrors.update { $0 = [.ReauthRequired] }
        let owner = FakeDeviceOwner()
        let model = deletion(core, owner, account: kakao)

        let first = await model.delete(online: true)
        XCTAssertFalse(first)
        XCTAssertEqual(model.problem, .reauthRequired)

        let second = await model.delete(online: true)
        XCTAssertTrue(second)
        XCTAssertEqual(owner.asked, [.deleteAccount])
        XCTAssertEqual(core.calls.current, ["nonce", "delete", "nonce", "delete"])
    }

    func testClosingTheProviderSheetDeletesNothingAndSaysNothing() async {
        let core = AccountFakeCore()
        let providers = FakeProviders()
        providers.result = .failure(.cancelled)
        let model = deletion(core, FakeDeviceOwner(), account: kakao, providers: providers)

        let deleted = await model.delete(online: true)

        XCTAssertFalse(deleted)
        XCTAssertNil(model.problem)
        XCTAssertEqual(core.calls.current, ["nonce"])
    }

    func testUploadsAGuestsRecoveryMaterialAndDeletesAgain() async {
        let core = AccountFakeCore()
        core.deleteErrors.update { $0 = [.RecoveryMissing] }
        let model = deletion(core, FakeDeviceOwner())

        let deleted = await model.delete(online: true)

        XCTAssertTrue(deleted)
        XCTAssertEqual(core.calls.current, ["delete", "sync", "delete"])
        XCTAssertFalse(model.preparing)
    }

    func testSaysSoWhenTheRecoveryMaterialStillIsNotThere() async {
        let core = AccountFakeCore()
        core.deleteErrors.update { $0 = [.RecoveryMissing, .RecoveryMissing] }
        let model = deletion(core, FakeDeviceOwner())

        let deleted = await model.delete(online: true)

        XCTAssertFalse(deleted)
        XCTAssertEqual(model.problem, .recoveryMissing)
    }

    func testDeletingWaitsForAConnectionBeforeAsking() async {
        let core = AccountFakeCore()
        let owner = FakeDeviceOwner()
        let model = deletion(core, owner)

        let deleted = await model.delete(online: false)

        XCTAssertFalse(deleted)
        XCTAssertEqual(model.problem, .offline)
        XCTAssertEqual(owner.asked, [])
    }
}
