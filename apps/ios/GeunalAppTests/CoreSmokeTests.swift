import CoreBridge
import GeunalCore
import XCTest

/// The real core through the XCFramework: proves the binary links (SQLCipher needs Security and CoreFoundation) and
/// that the M0 surface runs on the simulator.
final class CoreSmokeTests: XCTestCase {
    /// A fresh store directory, removed when the test ends.
    private func makeDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory
    }

    func testOpensTheStoreReadsTheCipherVersionAndSyncs() async throws {
        let directory = try makeDirectory()
        let secrets = MemorySecretStore()

        let core = try Core.open(
            storeDir: directory.path(percentEncoded: false),
            apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(),
            secrets: secrets)

        let version = try core.cipherVersion()
        XCTAssertTrue(version.first?.isNumber == true, "SQLCipher version, got '\(version)'")
        // The core made the database key and kept it only in the secret store.
        XCTAssertEqual(secrets.get(name: "db_key")?.count, 32)
        try await core.sync()
    }

    func testSetsAStoreWhoseKeyIsGoneAsideAndStartsAnEmptyOne() throws {
        let directory = try makeDirectory()
        let path = directory.path(percentEncoded: false)
        _ = try Core.open(
            storeDir: path, apiBase: "http://127.0.0.1:8080", listener: ChangeStreamListener(),
            secrets: MemorySecretStore())

        // A secret store without the database key, as after the keychain items went: the old file can never open.
        let core = try Core.open(
            storeDir: path, apiBase: "http://127.0.0.1:8080", listener: ChangeStreamListener(),
            secrets: MemorySecretStore())

        XCTAssertNotNil(try core.storeReset())
        XCTAssertNil(core.session())
        let aside = try FileManager.default.contentsOfDirectory(
            at: directory.appendingPathComponent("unreadable"), includingPropertiesForKeys: nil)
        XCTAssertEqual(aside.count, 1)
    }
}
