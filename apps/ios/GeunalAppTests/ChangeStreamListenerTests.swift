import CoreBridge
import GeunalCore
import XCTest

final class ChangeStreamListenerTests: XCTestCase {
    func testDeliversChangesInOrderAndEnds() async {
        let listener = ChangeStreamListener()

        listener.onChange(change: .calendar)
        listener.onChange(change: .group(groupId: "g1"))
        listener.onChange(change: .chat(groupId: "g1"))
        listener.finish()

        var received: [Change] = []
        for await change in listener.changes {
            received.append(change)
        }
        XCTAssertEqual(received, [.calendar, .group(groupId: "g1"), .chat(groupId: "g1")])
    }
}
