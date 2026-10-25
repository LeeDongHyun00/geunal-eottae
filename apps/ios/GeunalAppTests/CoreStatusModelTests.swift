import GeunalCore
import XCTest

@testable import GeunalApp

@MainActor
final class CoreStatusModelTests: XCTestCase {
    func testReportsEveryStepWhenTheCoreWorks() async {
        let model = CoreStatusModel { FakeCore(version: .success("4.9.0 community")) }

        await model.start()

        XCTAssertEqual(model.open, .done("완료"))
        XCTAssertEqual(model.cipher, .done("4.9.0 community"))
        XCTAssertEqual(model.sync, .done("완료"))
        XCTAssertFalse(model.isRunning)
    }

    func testStopsAtOpenWhenTheStoreCannotBeOpened() async {
        let model = CoreStatusModel { throw CoreError.Store(reason: "file is not a database") }

        await model.start()

        XCTAssertEqual(model.open, .failed("저장소: file is not a database"))
        XCTAssertEqual(model.cipher, .waiting)
        XCTAssertEqual(model.sync, .waiting)
    }

    func testReportsASyncFailureAfterTheVersion() async {
        let model = CoreStatusModel {
            FakeCore(sync: .failure(.SecretStore(reason: "db_key is not 32 bytes")))
        }

        await model.start()

        XCTAssertEqual(model.cipher, .done("4.9.0 community"))
        XCTAssertEqual(model.sync, .failed("비밀 보관: db_key is not 32 bytes"))
    }

    func testStartsAgainFromTheBeginning() async {
        let model = CoreStatusModel { FakeCore() }

        await model.start()
        await model.start()

        XCTAssertEqual(model.open, .done("완료"))
        XCTAssertEqual(model.sync, .done("완료"))
    }
}
