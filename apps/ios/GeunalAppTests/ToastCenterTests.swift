import DesignSystem
import XCTest

@MainActor
final class ToastCenterTests: XCTestCase {
    func testRunsTheActionAndClosesAtOnce() {
        let center = ToastCenter()
        var undone = false

        center.show("카페 마감을 추가했어요", actionTitle: "되돌리기") { undone = true }
        center.performAction()

        XCTAssertTrue(undone)
        XCTAssertNil(center.current)
    }

    func testANewToastReplacesTheOldOne() {
        let center = ToastCenter()

        center.show("하나")
        center.show("둘")

        XCTAssertEqual(center.current?.message, "둘")
    }

    func testGoesAwayByItself() async throws {
        let center = ToastCenter(duration: .milliseconds(50))

        center.show("추가했어요")
        try await Task.sleep(for: .milliseconds(400))

        XCTAssertNil(center.current)
    }

    private let proposal = ToastCenter.Proposal(
        message: "매주 금요일 반복으로 바꿀까요?", declineTitle: "괜찮아요", acceptTitle: "반복으로 바꾸기")

    func testTheQuestionOutlivesItsToast() async throws {
        let center = ToastCenter(
            duration: .milliseconds(50), proposalDuration: .milliseconds(600), screenReaderRunning: { false })

        center.show("카페 마감을 추가했어요", actionTitle: "되돌리기", action: {}, proposal: proposal, onAccept: {})
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(center.current)
        XCTAssertEqual(center.question?.proposal, proposal)

        try await Task.sleep(for: .milliseconds(900))
        XCTAssertNil(center.question)
    }

    func testUndoTakesTheQuestionWithIt() {
        let center = ToastCenter(screenReaderRunning: { false })
        var undone = false
        var accepted = false

        center.show(
            "카페 마감을 추가했어요", actionTitle: "되돌리기", action: { undone = true }, proposal: proposal,
            onAccept: { accepted = true })
        center.performAction()

        XCTAssertTrue(undone)
        XCTAssertFalse(accepted)
        XCTAssertNil(center.current)
        XCTAssertNil(center.question)
    }

    func testAnsweringClosesOnlyTheCard() {
        let center = ToastCenter(screenReaderRunning: { false })
        var accepted = false

        center.show("카페 마감을 추가했어요", actionTitle: "되돌리기", action: {}, proposal: proposal, onAccept: {})
        center.declineProposal()
        XCTAssertNil(center.question)
        XCTAssertEqual(center.current?.message, "카페 마감을 추가했어요")

        center.show(
            "카페 마감을 추가했어요", actionTitle: "되돌리기", action: {}, proposal: proposal, onAccept: { accepted = true })
        center.acceptProposal()
        XCTAssertTrue(accepted)
        XCTAssertNil(center.question)
    }

    func testAScreenReaderKeepsTheQuestionUntilItIsAnswered() async throws {
        let center = ToastCenter(
            duration: .milliseconds(50), proposalDuration: .milliseconds(50), screenReaderRunning: { true })

        center.show("카페 마감을 추가했어요", actionTitle: "되돌리기", action: {}, proposal: proposal, onAccept: {})
        try await Task.sleep(for: .milliseconds(400))

        XCTAssertNil(center.current)
        XCTAssertNotNil(center.question)
        center.declineProposal()
        XCTAssertNil(center.question)
    }

    func testANewToastDropsTheOldQuestion() {
        let center = ToastCenter(screenReaderRunning: { false })

        center.show("카페 마감을 추가했어요", actionTitle: "되돌리기", action: {}, proposal: proposal, onAccept: {})
        center.show("PT를 추가했어요")

        XCTAssertNil(center.question)
        XCTAssertEqual(center.current?.message, "PT를 추가했어요")
    }
}
