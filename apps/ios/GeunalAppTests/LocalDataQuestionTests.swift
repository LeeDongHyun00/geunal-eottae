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

    func testAsksThenSettlesTheAnswerAndGoesOn() async {
        let core = AccountFakeCore()
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
}
