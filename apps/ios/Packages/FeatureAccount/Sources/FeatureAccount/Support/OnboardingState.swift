import Foundation

/// What onboarding remembers between launches, in the standard defaults (no secrets: those are in the keychain).
public struct OnboardingState: Sendable {
    private static let finishedKey = "app.geunal.onboarding.finished"
    private static let pendingGuestKey = "app.geunal.onboarding.pendingGuestAgeConfirmedAt"

    private nonisolated(unsafe) let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The person reached the calendar once; later launches skip onboarding.
    public var isFinished: Bool {
        get { defaults.bool(forKey: Self.finishedKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.finishedKey) }
    }

    /// A guest who started offline: the time they ticked "만 14세 이상이에요", kept until the guest account can be made
    /// on the first connection (m1.md screen 2, decision 16). Only the time is kept, never a birth date.
    public var pendingGuestAgeConfirmedAt: Date? {
        get { defaults.object(forKey: Self.pendingGuestKey) as? Date }
        nonmutating set {
            if let newValue {
                defaults.set(newValue, forKey: Self.pendingGuestKey)
            } else {
                defaults.removeObject(forKey: Self.pendingGuestKey)
            }
        }
    }
}
