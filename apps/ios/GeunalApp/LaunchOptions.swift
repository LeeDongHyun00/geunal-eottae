#if DEBUG
    import CoreBridge
    import FeatureAccount
    import Foundation

    /// Launch arguments the UI tests pass, debug builds only: start from a fresh install, run on the preview core
    /// instead of the Rust one (whose M1 bodies are still landing), and pick a preview scenario.
    enum LaunchOptions {
        private static var arguments: [String] { ProcessInfo.processInfo.arguments }

        static var usesPreviewCore: Bool { arguments.contains("-GeunalPreviewCore") }

        /// `-GeunalDemoCalendar` writes the design's example week into the core after it opens (`DemoCalendar`).
        static var seedsDemoCalendar: Bool { arguments.contains("-GeunalDemoCalendar") }

        /// `-GeunalToastsWait` keeps a toast and its repeat card until they are answered or replaced. A UI test that
        /// taps one cannot promise to do so within five seconds on a slow simulator; `ToastCenterTests` covers the
        /// timing itself.
        static var toastsWait: Bool { arguments.contains("-GeunalToastsWait") }

        static var previewScenario: PreviewCore.Scenario {
            guard let index = arguments.firstIndex(of: "-GeunalPreviewScenario"), index + 1 < arguments.count else {
                return .standard
            }
            return PreviewCore.Scenario(rawValue: arguments[index + 1]) ?? .standard
        }

        /// `-GeunalResetState` makes the launch look like a first install: defaults, the store and the keychain go.
        static func resetIfAsked() {
            guard arguments.contains("-GeunalResetState") else { return }
            if let domain = Bundle.main.bundleIdentifier {
                UserDefaults.standard.removePersistentDomain(forName: domain)
            }
            if let store = try? StoreDirectory.url() {
                try? FileManager.default.removeItem(at: store)
            }
            KeychainSecretStore().deleteAll()
        }

        /// `-GeunalOnboardingFinished` skips onboarding for tests that start on the tabs. It comes after the check for a
        /// new installation, which would take it back on the real core: a reset leaves no store.
        static func skipOnboardingIfAsked(_ state: OnboardingState) {
            if arguments.contains("-GeunalOnboardingFinished") {
                state.isFinished = true
            }
        }
    }
#endif
