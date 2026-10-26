import CoreBridge
import FeatureAccount
import Foundation
import GeunalCore

/// How the app opens the Rust core: the store under Application Support, the keychain as the secret store, and a
/// listener that turns changes into a stream (docs/parts/ios.md §3).
enum AppCore {
    /// The API server. The local stack from `make up` publishes it here (README.md); the alpha server comes with its
    /// deployment.
    static let apiBase = ProcessInfo.processInfo.environment["GEUNAL_API_BASE"] ?? "http://127.0.0.1:8080"

    /// Kept for the life of the process: the core holds it, and the app reads its stream.
    static let listener = ChangeStreamListener()

    /// Kakao and Apple; the UI tests' preview core comes with stand-ins that sign in at once.
    @MainActor
    static func providers() -> any ProviderSignIn {
        #if DEBUG
            if LaunchOptions.usesPreviewCore {
                return PreviewProviderSignIn()
            }
        #endif
        return SystemProviderSignIn()
    }

    static func open() throws -> any CoreProtocol {
        #if DEBUG
            if LaunchOptions.usesPreviewCore {
                return PreviewCore(scenario: LaunchOptions.previewScenario)
            }
        #endif
        let storeDir = try StoreDirectory.url()
        return try Core.open(
            storeDir: storeDir.path(percentEncoded: false),
            apiBase: apiBase,
            listener: listener,
            secrets: KeychainSecretStore())
    }
}
