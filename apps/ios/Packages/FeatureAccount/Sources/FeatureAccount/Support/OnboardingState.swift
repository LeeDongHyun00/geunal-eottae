import Foundation
import GeunalCore

/// What kind of account this device last had a session for: what "다시 로그인" means once the core ended the session
/// (m1.md §4 "로그인이 끝났을 때", decision 43). No name, no id: only the kind.
public enum AccountKind: String, Sendable {
    case guest
    case provider
}

/// What onboarding remembers between launches, in the standard defaults (no secrets: those are in the keychain).
public struct OnboardingState: Sendable {
    private static let finishedKey = "app.geunal.onboarding.finished"
    private static let pendingGuestKey = "app.geunal.onboarding.pendingGuestAgeConfirmedAt"
    private static let revocationShownKey = "app.geunal.account.revocationShownAt"
    private static let lastProviderKey = "app.geunal.account.lastProvider"
    private static let lastProviderSignInKey = "app.geunal.account.lastProviderSignInAt"
    private static let knownAccountKey = "app.geunal.account.kind"
    private static let sessionEndedKey = "app.geunal.account.sessionEnded"

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
        forgetAccount()
    }

    /// The account was deleted: what this device remembered about it goes, and onboarding comes back for the next one.
    public func forgetAccount() {
        isFinished = false
        revocationShownAt = nil
        lastProviderSignIn = nil
        knownAccount = nil
        sessionEnded = false
    }

    /// The kind of the account this device last had a session for; `nil` before any, and after a deletion.
    public var knownAccount: AccountKind? {
        get { defaults.string(forKey: Self.knownAccountKey).flatMap(AccountKind.init(rawValue:)) }
        nonmutating set {
            if let newValue {
                defaults.set(newValue.rawValue, forKey: Self.knownAccountKey)
            } else {
                defaults.removeObject(forKey: Self.knownAccountKey)
            }
        }
    }

    /// The core ended the session and the toast said so: it comes once, until the next sign-in (decision 43).
    public var sessionEnded: Bool {
        get { defaults.bool(forKey: Self.sessionEndedKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.sessionEndedKey) }
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

    /// The last Kakao or Apple sign-in on this device. Starting fresh more than 30 minutes after it needs that
    /// provider's proof, and with both linked it is the provider asked (m1.md screen 21, decision 25).
    public var lastProviderSignIn: (provider: Provider, at: Date)? {
        get {
            guard let name = defaults.string(forKey: Self.lastProviderKey),
                let at = defaults.object(forKey: Self.lastProviderSignInKey) as? Date
            else { return nil }
            switch name {
            case "kakao": return (.kakao, at)
            case "apple": return (.apple, at)
            default: return nil
            }
        }
        nonmutating set {
            guard let newValue else {
                defaults.removeObject(forKey: Self.lastProviderKey)
                defaults.removeObject(forKey: Self.lastProviderSignInKey)
                return
            }
            defaults.set(newValue.provider == .kakao ? "kakao" : "apple", forKey: Self.lastProviderKey)
            defaults.set(newValue.at, forKey: Self.lastProviderSignInKey)
        }
    }
}
