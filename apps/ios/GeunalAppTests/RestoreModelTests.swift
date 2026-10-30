import FeatureAccount
import GeunalCore
import XCTest

@MainActor
final class RestoreModelTests: XCTestCase {
    private let code = ["바람", "연필", "구름", "달력", "열쇠", "우산", "단추", "마당", "편지", "소풍", "거울", "하늘"]
    @MainActor
    private struct Fixture {
        let core = AccountFakeCore()
        let owner = FakeDeviceOwner()
        let providers = FakeProviders()
        let toasts = ToastLog()

        /// `signedInAgo` is the time since this device's last Kakao sign-in, `nil` when it is not known.
        func model(
            _ mode: RestoreModel.Mode = .deviceChange, ageConfirmedAt: Date? = nil, signedInAgo: TimeInterval? = 60
        ) -> RestoreModel {
            let toasts = self.toasts
            let now = Date(timeIntervalSince1970: 1_793_000_000)
            return RestoreModel(
                mode: mode, core: core, owner: owner, providers: providers,
                lastSignIn: signedInAgo.map { (.kakao, now.addingTimeInterval(-$0)) }, ageConfirmedAt: ageConfirmedAt,
                now: { now }, notify: { toasts.append($0) })
        }
    }

    private func type(_ text: String, into index: Int, of model: RestoreModel) async {
        model.focused = index
        model.cells[index] = text
        await model.edited(index)
    }

    func testTakesAWholeWordAndMovesOn() async {
        let f = Fixture()
        let model = f.model()

        await type("바람", into: 0, of: model)

        XCTAssertEqual(model.accepted[0], "바람")
        XCTAssertEqual(model.focused, 1)
        XCTAssertEqual(model.acceptedCount, 1)
    }

    func testOnlyHintsAtTheWordAPrefixLeadsTo() async {
        let f = Fixture()
        let model = f.model()

        await type("가이", into: 0, of: model)

        // The text stays as typed: on the way to "가입" the IME passes through "가이".
        XCTAssertEqual(model.cells[0], "가이")
        XCTAssertNil(model.accepted[0])
        XCTAssertEqual(model.completions[0], "가이드")
        XCTAssertEqual(model.focused, 0)
    }

    func testTakesTheHintWhenThePersonLeavesTheCell() async {
        let f = Fixture()
        let model = f.model()
        await type("가이", into: 0, of: model)

        await model.committed(0)

        XCTAssertEqual(model.cells[0], "가이드")
        XCTAssertEqual(model.accepted[0], "가이드")
    }

    func testKeepsTheCellThePersonTappedWhenTheLastOneTakesItsHint() async {
        let f = Fixture()
        let model = f.model()
        await type("가이", into: 0, of: model)

        // The view moves the focus first, then commits the cell that was left.
        model.focused = 5
        await model.committed(0)

        XCTAssertEqual(model.accepted[0], "가이드")
        XCTAssertEqual(model.focused, 5)
    }

    func testGoesOnToTheNextCellOnReturn() async {
        let f = Fixture()
        let model = f.model()
        await type("가이", into: 0, of: model)

        // Return may drop the focus before the model hears about it.
        model.focused = nil
        await model.submitted(0)

        XCTAssertEqual(model.accepted[0], "가이드")
        XCTAssertEqual(model.focused, 1)
    }

    func testMarksAWordOutsideTheListOnceTheCellIsLeft() async {
        let f = Fixture()
        let model = f.model()
        await type("바다", into: 7, of: model)
        XCTAssertNil(model.problem)

        model.focused = 8
        await model.committed(7)

        XCTAssertEqual(model.problem, .unknownWord(index: 7))
        XCTAssertTrue(model.isUnknown(7))
    }

    func testFillsAllTwelveFromAPastedCode() async {
        let f = Fixture()
        let model = f.model()

        await model.paste("1. 바람 2. 연필 3. 구름 4. 달력 5. 열쇠 6. 우산\n7. 단추 8. 마당 9. 편지 10. 소풍 11. 거울 12. 하늘")

        XCTAssertEqual(model.accepted.compactMap { $0 }, code)
        XCTAssertTrue(model.isComplete)
        XCTAssertNil(model.focused)
    }

    func testRestoresWithTheCode() async {
        let f = Fixture()
        let model = f.model()
        await model.paste(code.joined(separator: " "))

        await model.start()

        XCTAssertEqual(f.core.calls.current.last, "restore " + code.joined(separator: " "))
        guard case .finished(let summary) = model.phase else { return XCTFail("phase is \(model.phase)") }
        XCTAssertEqual(summary.events, 3)
    }

    func testAsksAboutAnotherAccountsEventsFirst() async {
        let f = Fixture()
        f.core.otherAccountData = LocalData(events: 86, people: 5)
        let model = f.model()
        await model.paste(code.joined(separator: " "))

        await model.start()
        XCTAssertEqual(model.phase, .askingLocalData(LocalData(events: 86, people: 5)))
        await model.choose(.discard)

        XCTAssertEqual(f.core.lastLocalData.current, .discard)
    }

    func testRestoresOnceWhenTheButtonIsTappedTwice() async {
        let f = Fixture()
        // The second tap lands while the core still looks for another account's events.
        f.core.localDataDelay = 0.2
        let model = f.model()
        await model.paste(code.joined(separator: " "))

        async let first: Void = model.start()
        async let second: Void = model.start()
        _ = await (first, second)

        XCTAssertEqual(f.core.calls.current.filter { $0.hasPrefix("restore") }.count, 1)
        XCTAssertFalse(model.isWorking)
    }

    func testTakesTheAnswerAboutTheLocalEventsOnce() async {
        let f = Fixture()
        f.core.otherAccountData = LocalData(events: 86, people: 5)
        let model = f.model()
        await model.paste(code.joined(separator: " "))
        await model.start()
        XCTAssertFalse(model.isWorking)

        async let first: Void = model.choose(.keep)
        async let second: Void = model.choose(.discard)
        _ = await (first, second)

        XCTAssertEqual(f.core.calls.current.filter { $0.hasPrefix("restore") }.count, 1)
    }

    func testSaysTheCodeDoesNotOpenTheBackup() async {
        let f = Fixture()
        f.core.restoreResult = .failure(.WrongRecoveryCode)
        let model = f.model()
        await model.paste(code.joined(separator: " "))

        await model.start()

        XCTAssertEqual(model.problem, .wrongCode)
        XCTAssertEqual(model.phase, .entering)
    }

    func testSendsThePersonBackWhenTheTicketRanOut() async {
        let f = Fixture()
        f.core.restoreResult = .failure(.SignInAgain)
        let model = f.model()
        await model.paste(code.joined(separator: " "))

        await model.start()

        XCTAssertEqual(model.phase, .ticketExpired)
        XCTAssertEqual(f.toasts.messages, ["시간이 지나 처음부터 다시 로그인해 주세요"])
    }

    func testStartsFreshWithoutTheCodeOnceTheOwnerPasses() async {
        let f = Fixture()
        let model = f.model(.deviceChange)

        let started = await model.startWithoutCode()

        XCTAssertTrue(started)
        XCTAssertEqual(f.owner.asked, [.startFresh])
        XCTAssertEqual(f.core.calls.current, ["startFresh"])
        XCTAssertEqual(model.phase, .startedFresh)
    }

    func testCancellingTheOwnerPromptDeletesNoBackup() async {
        let f = Fixture()
        f.owner.passes = false
        let model = f.model(.offer)

        let started = await model.startWithoutCode()

        XCTAssertFalse(started)
        XCTAssertEqual(f.core.calls.current, [])
        XCTAssertEqual(model.phase, .entering)
        XCTAssertFalse(model.isWorking)
    }

    func testANewGuestAccountDeletesNothingSoTheOwnerIsNotAsked() async {
        let f = Fixture()
        let model = f.model(.guestReturn, ageConfirmedAt: Date())

        let started = await model.startWithoutCode()

        XCTAssertTrue(started)
        XCTAssertEqual(f.owner.asked, [])
        XCTAssertEqual(f.core.calls.current, ["guest"])
    }

    func testADeviceWithoutAScreenLockStartsFreshAndSaysSo() async {
        let f = Fixture()
        f.owner.hasScreenLock = false
        f.owner.passes = false
        let model = f.model(.deviceChange)

        XCTAssertFalse(model.screenLocked)
        let started = await model.startWithoutCode()

        XCTAssertTrue(started)
        XCTAssertEqual(f.core.calls.current, ["startFresh"])
    }

    func testANewGuestNeedsTheAgeTickFirst() async {
        let f = Fixture()
        let model = f.model(.guestReturn, ageConfirmedAt: nil)

        let started = await model.startWithoutCode()

        XCTAssertFalse(started)
        XCTAssertEqual(f.core.calls.current, [])
    }

    // MARK: The checksum (m1.md screen 21)

    func testTwelveWordsWithAWrongChecksumNeverReachTheServer() async {
        let f = Fixture()
        f.core.codeCheck = .checksum
        let model = f.model()
        await model.paste(code.joined(separator: " "))

        XCTAssertEqual(model.problem, .checksum)
        XCTAssertFalse(model.canStart)
        await model.start()
        XCTAssertEqual(f.core.calls.current, [])
    }

    func testFixingAWordClearsTheChecksumProblem() async {
        let f = Fixture()
        f.core.codeCheck = .checksum
        let model = f.model()
        await model.paste(code.joined(separator: " "))
        f.core.codeCheck = .valid

        await type("하늘", into: 11, of: model)

        XCTAssertNil(model.problem)
        XCTAssertTrue(model.canStart)
    }

    // MARK: "나중에 넣을게요" and starting fresh (decisions 24, 25)

    func testTheOfferCanWaitForTheCode() {
        let model = Fixture().model(.offer)

        model.later()

        XCTAssertEqual(model.phase, .later)
    }

    func testOnlyTheOfferCanWait() {
        let model = Fixture().model(.deviceChange)

        model.later()

        XCTAssertEqual(model.phase, .entering)
    }

    func testCountsTheBackupsTheConfirmationDeletes() async {
        let f = Fixture()
        f.core.backupList = [
            BackupView(id: 2, createdAt: Date(), size: 1, automatic: true, events: 3, people: 1),
            BackupView(
                id: 1, createdAt: Date(timeIntervalSinceNow: -86_400), size: 1, automatic: true, events: 2, people: 1),
        ]
        let model = f.model(.offer)

        await model.loadBackups()

        XCTAssertEqual(model.backupsToDelete?.count, 2)
    }

    func testStartsFreshWithoutAProofWithinThirtyMinutesOfTheSignIn() async {
        let f = Fixture()
        let model = f.model(.offer, signedInAgo: 29 * 60)

        XCTAssertFalse(model.needsProof)
        let started = await model.startWithoutCode()

        XCTAssertTrue(started)
        XCTAssertEqual(f.core.freshProofs.current, [nil])
        XCTAssertEqual(f.providers.nonces, [])
    }

    func testChecksWithKakaoAfterThirtyMinutes() async {
        let f = Fixture()
        let model = f.model(.offer, signedInAgo: 31 * 60)

        XCTAssertTrue(model.needsProof)
        let started = await model.startWithoutCode()

        XCTAssertTrue(started)
        XCTAssertEqual(f.providers.nonces, ["p1"])
        XCTAssertEqual(f.core.freshProofs.current, [.kakao(nonce: "n1", idToken: "token")])
        XCTAssertEqual(model.phase, .startedFresh)
    }

    func testTheDeviceChangeNeverNeedsAProof() {
        XCTAssertFalse(Fixture().model(.deviceChange, signedInAgo: nil).needsProof)
    }

    func testOnlyTurnsTheButtonIntoTheCheckWhenTheThirtyMinutesRanOutMeanwhile() async {
        let f = Fixture()
        f.core.startFreshErrors.update { $0 = [.ReauthRequired] }
        let model = f.model(.offer)

        let started = await model.startWithoutCode()

        XCTAssertFalse(started)
        XCTAssertTrue(model.needsProof)
        XCTAssertNil(model.freshProblem)
        XCTAssertEqual(f.core.freshProofs.current, [nil])
    }

    func testSaysAnotherKakaoAccountCannotStartFreshAndAsksTheOwnerOnce() async {
        let f = Fixture()
        f.core.startFreshErrors.update { $0 = [.ReauthRequired] }
        let model = f.model(.offer, signedInAgo: nil)

        let first = await model.startWithoutCode()
        XCTAssertFalse(first)
        XCTAssertEqual(model.freshProblem, .otherAccount)
        XCTAssertEqual(model.phase, .entering)

        let second = await model.startWithoutCode()
        XCTAssertTrue(second)
        XCTAssertNil(model.freshProblem)
        XCTAssertEqual(f.owner.asked, [.startFresh])
        XCTAssertEqual(f.core.freshProofs.current.count, 2)
    }

    func testAClosedProviderSheetDeletesNothingAndAClosedSheetAsksTheOwnerAgain() async {
        let f = Fixture()
        f.providers.result = .failure(.cancelled)
        let model = f.model(.offer, signedInAgo: nil)

        let started = await model.startWithoutCode()
        model.noCodeClosed()
        _ = await model.startWithoutCode()

        XCTAssertFalse(started)
        XCTAssertNil(model.freshProblem)
        XCTAssertFalse(f.core.calls.current.contains("startFresh"))
        XCTAssertEqual(f.owner.asked, [.startFresh, .startFresh])
    }

    func testSaysTheConnectionIsMissingInTheConfirmation() async {
        let f = Fixture()
        f.core.startFreshErrors.update { $0 = [.Network(reason: "offline")] }
        let model = f.model(.deviceChange)

        let started = await model.startWithoutCode()

        XCTAssertFalse(started)
        XCTAssertEqual(model.freshProblem, .offline)
        XCTAssertNil(model.problem)
    }

    func testSendsThePersonBackWhenTheTicketRanOutBeforeStartingFresh() async {
        let f = Fixture()
        f.core.startFreshErrors.update { $0 = [.SignInAgain] }
        let model = f.model(.deviceChange)

        let started = await model.startWithoutCode()

        XCTAssertFalse(started)
        XCTAssertEqual(model.phase, .ticketExpired)
        XCTAssertEqual(f.toasts.messages, ["시간이 지나 처음부터 다시 로그인해 주세요"])
    }
}
