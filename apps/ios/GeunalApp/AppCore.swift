import CoreBridge
import Foundation
import GeunalCore

/// How the app opens the Rust core: the store under Application Support, the keychain as the secret store, and a
/// listener that turns changes into a stream (docs/parts/ios.md §3).
enum AppCore {
    /// The API server. The local stack from `make up` publishes it here (README.md); the alpha server comes with M1.
    static let apiBase = ProcessInfo.processInfo.environment["GEUNAL_API_BASE"] ?? "http://127.0.0.1:8080"

    /// Kept for the life of the process: the core holds it, and the stream it feeds is what M1 screens will read.
    static let listener = ChangeStreamListener()

    static func open() throws -> any CoreProtocol {
        let storeDir = try StoreDirectory.url()
        return try Core.open(
            storeDir: storeDir.path(percentEncoded: false),
            apiBase: apiBase,
            listener: listener,
            secrets: KeychainSecretStore())
    }
}
