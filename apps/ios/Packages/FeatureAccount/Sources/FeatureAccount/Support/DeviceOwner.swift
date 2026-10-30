import Foundation

#if canImport(LocalAuthentication)
    import LocalAuthentication
#endif

/// The three things someone who finds an unlocked phone must not do, and the reason line the OS prompt shows for each
/// (m1.md decision 26, tone.md "본인 확인").
public enum DeviceOwnerReason: Sendable {
    case recoveryCode
    case startFresh
    case deleteAccount

    public var text: String {
        switch self {
        case .recoveryCode: String(localized: "복구 코드를 보려면 본인 확인이 필요해요", bundle: .module)
        case .startFresh: String(localized: "백업을 지우려면 본인 확인이 필요해요", bundle: .module)
        case .deleteAccount: String(localized: "계정을 삭제하려면 본인 확인이 필요해요", bundle: .module)
        }
    }
}

/// The OS prompt for Face ID, Touch ID or the passcode (m1.md §4 "기기 인증"). The app draws nothing of its own. A device
/// without a screen lock is let through, because deleting the account has to stay possible; the screens recommend a
/// lock instead.
@MainActor
public protocol DeviceOwnerCheck {
    /// `false` on a device without a passcode, so the screen adds the line that recommends one.
    var hasScreenLock: Bool { get }
    /// `true` once the prompt passed, and at once on a device without a screen lock. Cancelling or failing is `false`,
    /// and the caller then does nothing.
    func confirm(_ reason: DeviceOwnerReason) async -> Bool
}

#if canImport(LocalAuthentication)
    /// `LAContext` with `.deviceOwnerAuthentication`: Face ID or Touch ID, and the passcode when those fail.
    public struct SystemDeviceOwnerCheck: DeviceOwnerCheck {
        public init() {}

        public var hasScreenLock: Bool {
            var error: NSError?
            return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) || !Self.noPasscode(error)
        }

        public func confirm(_ reason: DeviceOwnerReason) async -> Bool {
            let context = LAContext()
            var error: NSError?
            guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
                return Self.noPasscode(error)
            }
            let text = reason.text
            let passed = await withCheckedContinuation { continuation in
                // LocalAuthentication replies on its own queue; a closure isolated to the main actor would trap there.
                context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: text) { @Sendable (passed, _) in
                    continuation.resume(returning: passed)
                }
            }
            // A context released mid-prompt cancels it, so it lives until the answer is in.
            withExtendedLifetime(context) {}
            return passed
        }

        /// Only a missing passcode lets the action through. Any other reason the prompt cannot run counts as not passed.
        private static func noPasscode(_ error: NSError?) -> Bool {
            guard let error else { return false }
            return error.domain == LAErrorDomain && error.code == LAError.Code.passcodeNotSet.rawValue
        }
    }
#endif
