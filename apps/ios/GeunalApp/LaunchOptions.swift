#if DEBUG
    import CoreBridge
    import FeatureAccount
    import Foundation

    /// Launch arguments the UI tests pass, debug builds only: start from a fresh install, run on the preview core
    /// instead of the Rust one (whose M1 bodies are still landing), and pick a preview scenario.
    enum LaunchOptions {
        private static var arguments: [String] { ProcessInfo.processInfo.arguments }

        static var usesPreviewCore: Bool { arguments.contains("-GeunalPreviewCore") }

        static var previewScenario: PreviewCore.Scenario {
            guard let index = arguments.firstIndex(of: "-GeunalPreviewScenario"), index + 1 < arguments.count else {
                return .standard
            }
            return PreviewCore.Scenario(rawValue: arguments[index + 1]) ?? .standard
        }

        /// `-GeunalResetState` makes the launch look like a first install: defaults, the store and the keychain go.
        /// `-GeunalOnboardingFinished` skips onboarding for tests that start on the tabs.
        static func prepare() {
            let defaults = UserDefaults.standard
            if arguments.contains("-GeunalResetState") {
                if let domain = Bundle.main.bundleIdentifier {
                    defaults.removePersistentDomain(forName: domain)
                }
                if let store = try? StoreDirectory.url() {
                    try? FileManager.default.removeItem(at: store)
                }
                KeychainSecretStore().deleteAll()
            }
            if arguments.contains("-GeunalOnboardingFinished") {
                OnboardingState(defaults: defaults).isFinished = true
            }
        }
    }
#endif
