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

    /// Face ID, Touch ID or the passcode before the recovery code, starting fresh and deleting the account. The UI
    /// tests' stand-in passes at once, since the simulator cannot answer the system prompt.
    @MainActor
    static func deviceOwner() -> any DeviceOwnerCheck {
        #if DEBUG
            if LaunchOptions.usesPreviewCore {
                return PreviewDeviceOwnerCheck(hasScreenLock: !LaunchOptions.hasNoScreenLock)
            }
        #endif
        return SystemDeviceOwnerCheck()
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
