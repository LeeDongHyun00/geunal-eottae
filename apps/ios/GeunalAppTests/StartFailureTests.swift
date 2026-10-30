import Foundation
import GeunalCore
import XCTest

@testable import GeunalApp

/// The start failure screen names the kind of failure only (m1.md §4 "시작 오류", decision 37).
@MainActor
final class StartFailureTests: XCTestCase {
    func testNamesTheKindOfFailureAndNotTheCoresWords() {
        XCTAssertEqual(StartFailure(CoreError.SecretStore(reason: "db_key is not 32 bytes")), .key)
        XCTAssertEqual(StartFailure(CoreError.Store(reason: "file is not a database")), .store)
        XCTAssertEqual(StartFailure(CoreError.Network(reason: "offline")), .unknown)
        XCTAssertEqual(StartFailure(CocoaError(.fileWriteNoPermission)), .unknown)
    }
}
