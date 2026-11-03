import CoreBridge
import FeatureAccount
import XCTest

@testable import GeunalApp

final class FreshInstallTests: XCTestCase {
    private let keychain = KeychainSecretStore(service: "app.geunal.tests.\(UUID().uuidString)")

    override func tearDown() {
        keychain.deleteAll()
        super.tearDown()
    }

    private func makeStoreDirectory(withDatabase: Bool) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        if withDatabase {
            let database = directory.appendingPathComponent(FreshInstall.databaseFile).path(percentEncoded: false)
            XCTAssertTrue(FileManager.default.createFile(atPath: database, contents: Data([0])))
        }
        return directory
    }

    func testWipesThePreviousInstallsSecretsWhenThereIsNoStore() throws {
        let directory = try makeStoreDirectory(withDatabase: false)
        try keychain.put(name: "db_key", value: Data([1, 2, 3]))
        try keychain.put(name: "session", value: Data([4]))

        let wiped = FreshInstall.wipeLeftovers(storeDirectory: directory, keychain: keychain)

        XCTAssertTrue(wiped)
        XCTAssertNil(try keychain.get(name: "db_key"))
        XCTAssertNil(try keychain.get(name: "session"))
    }

    func testKeepsTheKeysOfAnInstallationThatHasAStore() throws {
        // An upgrade from an earlier build: its database needs this key, or the store can never be opened again.
        let directory = try makeStoreDirectory(withDatabase: true)
        try keychain.put(name: "db_key", value: Data([1, 2, 3]))

        let wiped = FreshInstall.wipeLeftovers(storeDirectory: directory, keychain: keychain)

        XCTAssertFalse(wiped)
        XCTAssertEqual(try keychain.get(name: "db_key"), Data([1, 2, 3]))
    }

    func testShowsOnboardingWhenTheDefaultsCameFromAnotherDevice() throws {
        // An iCloud restore or Quick Start brings the defaults, but neither the store nor the keychain items.
        let directory = try makeStoreDirectory(withDatabase: false)
        let onboarding = OnboardingState(defaults: scratchDefaults())
        onboarding.isFinished = true
        onboarding.pendingGuestAgeConfirmedAt = Date()

        AppCore.forgetPreviousInstallation(storeDirectory: directory, keychain: keychain, onboarding: onboarding)

        XCTAssertFalse(onboarding.isFinished)
        XCTAssertNil(onboarding.pendingGuestAgeConfirmedAt)
    }

    func testKeepsTheOnboardingOfAnInstallationThatHasAStore() throws {
        let directory = try makeStoreDirectory(withDatabase: true)
        let onboarding = OnboardingState(defaults: scratchDefaults())
        onboarding.isFinished = true
        let ticked = Date(timeIntervalSince1970: 1_790_000_000)
        onboarding.pendingGuestAgeConfirmedAt = ticked

        AppCore.forgetPreviousInstallation(storeDirectory: directory, keychain: keychain, onboarding: onboarding)

        XCTAssertTrue(onboarding.isFinished)
        XCTAssertEqual(onboarding.pendingGuestAgeConfirmedAt, ticked)
    }
}
