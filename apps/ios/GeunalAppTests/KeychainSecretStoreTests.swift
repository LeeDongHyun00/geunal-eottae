import CoreBridge
import XCTest

final class KeychainSecretStoreTests: XCTestCase {
    // One service per test run, so a leftover from an aborted run cannot leak into the assertions.
    private let store = KeychainSecretStore(service: "app.geunal.tests.\(UUID().uuidString)")

    override func tearDown() {
        store.delete(name: "db_key")
        store.delete(name: "other")
        super.tearDown()
    }

    func testReturnsNothingForAnUnknownName() {
        XCTAssertNil(store.get(name: "db_key"))
    }

    func testRoundTripsAValue() {
        let key = Data((0..<32).map { UInt8($0) })

        store.put(name: "db_key", value: key)

        XCTAssertEqual(store.get(name: "db_key"), key)
        XCTAssertNil(store.get(name: "other"))
    }

    func testOverwritesAnExistingValue() {
        store.put(name: "db_key", value: Data([1, 2, 3]))

        store.put(name: "db_key", value: Data([4, 5, 6]))

        XCTAssertEqual(store.get(name: "db_key"), Data([4, 5, 6]))
    }

    func testDeletesAValueAndToleratesDeletingItAgain() {
        store.put(name: "db_key", value: Data([1]))

        store.delete(name: "db_key")
        store.delete(name: "db_key")

        XCTAssertNil(store.get(name: "db_key"))
    }

    func testKeepsServicesApart() {
        let other = KeychainSecretStore(service: "app.geunal.tests.\(UUID().uuidString)")
        defer { other.delete(name: "db_key") }
        store.put(name: "db_key", value: Data([1]))

        XCTAssertNil(other.get(name: "db_key"))
    }
}
