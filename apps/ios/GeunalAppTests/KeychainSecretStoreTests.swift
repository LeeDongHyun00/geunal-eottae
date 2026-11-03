import CoreBridge
import XCTest

final class KeychainSecretStoreTests: XCTestCase {
    // One service per test run, so a leftover from an aborted run cannot leak into the assertions.
    private let store = KeychainSecretStore(service: "app.geunal.tests.\(UUID().uuidString)")

    override func tearDown() {
        try? store.delete(name: "db_key")
        try? store.delete(name: "other")
        super.tearDown()
    }

    func testReturnsNothingForAnUnknownName() throws {
        XCTAssertNil(try store.get(name: "db_key"))
    }

    func testRoundTripsAValue() throws {
        let key = Data((0..<32).map { UInt8($0) })

        try store.put(name: "db_key", value: key)

        XCTAssertEqual(try store.get(name: "db_key"), key)
        XCTAssertNil(try store.get(name: "other"))
    }

    func testOverwritesAnExistingValue() throws {
        try store.put(name: "db_key", value: Data([1, 2, 3]))

        try store.put(name: "db_key", value: Data([4, 5, 6]))

        XCTAssertEqual(try store.get(name: "db_key"), Data([4, 5, 6]))
    }

    func testDeletesAValueAndToleratesDeletingItAgain() throws {
        try store.put(name: "db_key", value: Data([1]))

        try store.delete(name: "db_key")
        try store.delete(name: "db_key")

        XCTAssertNil(try store.get(name: "db_key"))
    }

    func testKeepsServicesApart() throws {
        let other = KeychainSecretStore(service: "app.geunal.tests.\(UUID().uuidString)")
        defer { try? other.delete(name: "db_key") }
        try store.put(name: "db_key", value: Data([1]))

        XCTAssertNil(try other.get(name: "db_key"))
    }
}
