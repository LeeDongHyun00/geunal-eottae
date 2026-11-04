import FeatureAccount
import GeunalCore
import XCTest

/// m1.md screen 24 after an account is decided without a restore: the core keeps another account's events apart until
/// it has the answer.
@MainActor
final class LocalDataQuestionTests: XCTestCase {
    func testGoesOnAtOnceWithNothingToAsk() async {
        let core = AccountFakeCore()
        let question = LocalDataQuestion(core: core, notify: { _ in })
        let wentOn = Locked(false)

        await question.ask { wentOn.update { $0 = true } }

        XCTAssertTrue(wentOn.current)
        XCTAssertNil(question.asking)
    }

    func testWaitsForASessionBeforeAsking() async {
        // After a deletion or a sign-out the core still knows the old owner's events, but takes an answer only from a
        // signed-in account; asking now would hold onboarding on a sheet whose answers both fail.
        let core = AccountFakeCore()
        core.otherAccountData = LocalData(events: 86, people: 5)
        let question = LocalDataQuestion(core: core, notify: { _ in })
        let wentOn = Locked(false)

        await question.ask { wentOn.update { $0 = true } }

        XCTAssertTrue(wentOn.current)
        XCTAssertNil(question.asking)
        XCTAssertEqual(core.settled.current, [])
    }

    func testAsksThenSettlesTheAnswerAndGoesOn() async {
        let core = AccountFakeCore()
        core.signedIn = Session(userId: "u2", deviceId: "d1")
        core.otherAccountData = LocalData(events: 86, people: 5)
        let question = LocalDataQuestion(core: core, notify: { _ in })
        let wentOn = Locked(false)

        await question.ask { wentOn.update { $0 = true } }
        XCTAssertEqual(question.asking, LocalData(events: 86, people: 5))
        XCTAssertFalse(wentOn.current)

        await question.answer(.discard)

        XCTAssertEqual(core.settled.current, [.discard])
        XCTAssertNil(question.asking)
        XCTAssertTrue(wentOn.current)
    }

    func testKeepsAskingWhenTheAnswerCouldNotBeSaved() async {
        let core = AccountFakeCore()
        core.signedIn = Session(userId: "u2", deviceId: "d1")
        core.otherAccountData = LocalData(events: 1, people: 0)
        core.settleError = .Store(reason: "full")
        let toasts = ToastLog()
        let question = LocalDataQuestion(core: core, notify: { toasts.append($0) })
        let wentOn = Locked(false)
        await question.ask { wentOn.update { $0 = true } }

        await question.answer(.keep)

        XCTAssertNotNil(question.asking)
        XCTAssertFalse(wentOn.current)
        XCTAssertEqual(toasts.messages, ["지금은 할 수 없어요. 잠시 뒤에 다시 시도해 주세요"])
    }

    // A sign-out from another device while the sheet was up: the core takes no answer, so the sheet goes instead of
    // holding the screen until the app is opened again.
    func testWithdrawsWhenTheSessionEndedBeforeTheAnswer() async {
        let core = AccountFakeCore()
        core.signedIn = Session(userId: "u2", deviceId: "d1")
        core.otherAccountData = LocalData(events: 3, people: 0)
        core.settleError = .NotSignedIn
        let toasts = ToastLog()
        let question = LocalDataQuestion(core: core, notify: { toasts.append($0) })
        let wentOn = Locked(false)
        await question.ask { wentOn.update { $0 = true } }

        await question.answer(.keep)

        XCTAssertNil(question.asking)
        XCTAssertFalse(wentOn.current)
        XCTAssertEqual(toasts.messages, [])
    }
}
