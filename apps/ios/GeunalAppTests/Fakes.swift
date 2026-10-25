import Foundation
import GeunalCore

/// A core that answers with whatever the test decided (docs/parts/ios.md §9). It subclasses the generated `Core`
/// without a Rust handle, the way UniFFI provides for fakes, and overrides only what the status screen calls; any other
/// method traps. That keeps this fake unchanged as the core's surface grows.
final class FakeCore: Core, @unchecked Sendable {
    let version: Result<String, CoreError>
    let syncOutcome: Result<Void, CoreError>

    init(version: Result<String, CoreError> = .success("4.9.0 community"), sync: Result<Void, CoreError> = .success(()))
    {
        self.version = version
        self.syncOutcome = sync
        super.init(noHandle: Core.NoHandle())
    }

    required init(unsafeFromHandle handle: UInt64) {
        fatalError("a fake core never comes from Rust")
    }

    override func cipherVersion() throws -> String {
        try version.get()
    }

    override func sync() async throws {
        try syncOutcome.get()
    }

    override func session() -> Session? { nil }
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
