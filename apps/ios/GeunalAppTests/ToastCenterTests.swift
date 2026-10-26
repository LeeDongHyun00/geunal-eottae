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
}
