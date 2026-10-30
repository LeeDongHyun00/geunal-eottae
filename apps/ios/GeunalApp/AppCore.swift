import CoreBridge
import FeatureAccount
import Foundation
import GeunalCore

/// How the app opens the Rust core: the store under Application Support, the keychain as the secret store, and a
/// listener that turns changes into a stream (docs/parts/ios.md §3).
enum AppCore {
    /// The API server. A device or TestFlight build starts without Xcode's environment, so the address it needs comes
    /// in at build time: `GEUNAL_API_BASE` in Config/Secrets.xcconfig fills the `GeunalAPIBase` Info.plist entry.
    static let apiBase = resolveAPIBase(
        environment: ProcessInfo.processInfo.environment, info: Bundle.main.infoDictionary ?? [:],
        fallback: defaultAPIBase)

    /// Debug builds talk to the local stack that `make up` publishes (README.md). A release build has no server until
    /// it is given one: like Android's, the calendar works and every call fails.
    static var defaultAPIBase: String {
        #if DEBUG
            return "http://127.0.0.1:8080"
        #else
            return ""
        #endif
    }

    /// The first address that is set: the `GEUNAL_API_BASE` environment variable, with which a scheme or a UI test
    /// points the simulator elsewhere, then the `GeunalAPIBase` Info.plist entry, then `fallback`. An empty value
    /// counts as unset, because Info.plist gets an empty string from a build that has no address.
    static func resolveAPIBase(environment: [String: String], info: [String: Any], fallback: String) -> String {
        let candidates = [environment["GEUNAL_API_BASE"], info["GeunalAPIBase"] as? String]
        return candidates.compactMap { $0 }.first { !$0.isEmpty } ?? fallback
    }

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

    /// Before the core reads its database key. A launch without a store is a new installation on this phone: a
    /// reinstall, an iCloud restore or Quick Start. It must not inherit the keychain's secrets, nor the finished
    /// onboarding and the pending guest's tick that the defaults carried over from the other phone (`FreshInstall`).
    static func forgetPreviousInstallation(onboarding: OnboardingState) throws {
        #if DEBUG
            // The preview core keeps no store, so every launch on it would look new.
            if LaunchOptions.usesPreviewCore {
                return
            }
        #endif
        let store = try StoreDirectory.url()
        forgetPreviousInstallation(storeDirectory: store, keychain: KeychainSecretStore(), onboarding: onboarding)
    }

    static func forgetPreviousInstallation(
        storeDirectory: URL, keychain: KeychainSecretStore, onboarding: OnboardingState
    ) {
        if FreshInstall.wipeLeftovers(storeDirectory: storeDirectory, keychain: keychain) {
            onboarding.reset()
        }
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
