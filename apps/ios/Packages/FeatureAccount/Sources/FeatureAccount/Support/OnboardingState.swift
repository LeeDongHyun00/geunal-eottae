import Foundation

/// What onboarding remembers between launches, in the standard defaults (no secrets: those are in the keychain).
public struct OnboardingState: Sendable {
    private static let finishedKey = "app.geunal.onboarding.finished"
    private static let pendingGuestKey = "app.geunal.onboarding.pendingGuestAgeConfirmedAt"
    private static let revocationShownKey = "app.geunal.account.revocationShownAt"

    private nonisolated(unsafe) let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The person reached the calendar once; later launches skip onboarding.
    public var isFinished: Bool {
        get { defaults.bool(forKey: Self.finishedKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.finishedKey) }
    }

    /// Starts onboarding over: a launch without a store is a new installation, whatever the defaults brought along
    /// from a backup (`FreshInstall`).
    public func reset() {
        isFinished = false
        pendingGuestAgeConfirmedAt = nil
        revocationShownAt = nil
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

    /// When the revocation the "기기 로그아웃됨" sheet last showed happened (m1.md screen 23). The core keeps the
    /// revocation until the next sign-in, and the sheet comes once for each.
    public var revocationShownAt: Date? {
        get { defaults.object(forKey: Self.revocationShownKey) as? Date }
        nonmutating set {
            if let newValue {
                defaults.set(newValue, forKey: Self.revocationShownKey)
            } else {
                defaults.removeObject(forKey: Self.revocationShownKey)
            }
        }
    }
}
