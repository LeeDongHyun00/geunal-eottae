import Foundation
import GeunalCore

/// A core that answers with whatever the test decided (docs/parts/ios.md §9).
final class FakeCore: CoreProtocol {
    let version: Result<String, CoreError>
    let syncOutcome: Result<Void, CoreError>

    init(version: Result<String, CoreError> = .success("4.9.0 community"), sync: Result<Void, CoreError> = .success(()))
    {
        self.version = version
        self.syncOutcome = sync
    }

    func cipherVersion() throws -> String {
        try version.get()
    }

    func sync() async throws {
        try syncOutcome.get()
    }
}

/// A secret store in memory, for opening the real core in a test without touching the keychain.
final class MemorySecretStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Data] = [:]

    func get(name: String) -> Data? {
        lock.withLock { values[name] }
    }

    func put(name: String, value: Data) {
        lock.withLock { values[name] = value }
    }

    func delete(name: String) {
        lock.withLock { _ = values.removeValue(forKey: name) }
    }
}
