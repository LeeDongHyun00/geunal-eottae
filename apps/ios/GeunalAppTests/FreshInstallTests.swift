import CoreBridge
import XCTest

final class FreshInstallTests: XCTestCase {
    private let keychain = KeychainSecretStore(service: "app.geunal.tests.\(UUID().uuidString)")

    override func tearDown() {
        keychain.deleteAll()
        super.tearDown()
    }

    func testWipesThePreviousInstallsSecretsOnTheFirstLaunch() {
        let defaults = scratchDefaults()
        keychain.put(name: "db_key", value: Data([1, 2, 3]))
        keychain.put(name: "session", value: Data([4]))

        let wiped = FreshInstall.wipeLeftovers(defaults: defaults, keychain: keychain)

        XCTAssertTrue(wiped)
        XCTAssertNil(keychain.get(name: "db_key"))
        XCTAssertNil(keychain.get(name: "session"))
        XCTAssertTrue(defaults.bool(forKey: FreshInstall.markerKey))
    }

    func testLeavesTheSecretsAloneOnLaterLaunches() {
        let defaults = scratchDefaults()
        FreshInstall.wipeLeftovers(defaults: defaults, keychain: keychain)
        keychain.put(name: "db_key", value: Data([1, 2, 3]))

        let wiped = FreshInstall.wipeLeftovers(defaults: defaults, keychain: keychain)

        XCTAssertFalse(wiped)
        XCTAssertEqual(keychain.get(name: "db_key"), Data([1, 2, 3]))
    }
}
