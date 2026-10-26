import Foundation
import GeunalCore
import Observation

/// The sign-in screen (m1.md screen 2): the age check, Kakao, Apple and guest. It returns where onboarding goes next
/// and reports failures through `notify`, which shows a toast.
@MainActor
@Observable
public final class SignInModel {
    public enum Busy: Equatable, Sendable {
        case kakao
        case apple
        case guest
    }

    public enum Outcome: Equatable, Sendable {
        case permissions
        case restore(RestoreModel.Mode)
    }

    /// When the box was ticked; only this time goes to the server, never a birth date (kickoff §6).
    public private(set) var ageConfirmedAt: Date?
    public private(set) var busy: Busy?

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let providers: any ProviderSignIn
    @ObservationIgnored private let state: OnboardingState
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private let notify: @MainActor (String) -> Void

    public init(
        core: any CoreProtocol, providers: any ProviderSignIn, state: OnboardingState = OnboardingState(),
        now: @escaping @Sendable () -> Date = Date.init, notify: @escaping @MainActor (String) -> Void
    ) {
        self.core = core
        self.providers = providers
        self.state = state
        self.now = now
        self.notify = notify
    }

    public var isAgeConfirmed: Bool {
        get { ageConfirmedAt != nil }
        set { ageConfirmedAt = newValue ? now() : nil }
    }

    /// Kakao or Apple. `nil` when nothing changes: cancelled, failed (the toast says so) or already running.
    public func signIn(with provider: Provider, isOnline: Bool) async -> Outcome? {
        guard let ageConfirmedAt, busy == nil else { return nil }
        guard isOnline else {
            notify(Strings.offline)
            return nil
        }
        busy = provider == .kakao ? .kakao : .apple
        defer { busy = nil }
        do {
            let nonce = try await core.signInNonce()
            let result: AuthResult
            switch provider {
            case .kakao:
                let credential = try await providers.kakao(providerNonce: nonce.providerNonce)
                let account = NewAccount(
                    displayName: Self.displayName(credential.displayName, provider: provider),
                    ageConfirmedAt: ageConfirmedAt)
                result = try await core.signInKakao(nonce: nonce.nonce, idToken: credential.idToken, account: account)
            case .apple:
                let credential = try await providers.apple(providerNonce: nonce.providerNonce)
                let account = NewAccount(
                    displayName: Self.displayName(credential.displayName, provider: provider),
                    ageConfirmedAt: ageConfirmedAt)
                result = try await core.signInApple(nonce: nonce.nonce, idToken: credential.idToken, account: account)
            }
            state.pendingGuestAgeConfirmedAt = nil
            return Self.outcome(of: result)
        } catch ProviderSignInError.cancelled {
            return nil
        } catch CoreError.Network(_) {
            notify(Strings.offline)
            return nil
        } catch {
            notify(Strings.signInFailed)
            return nil
        }
    }

    /// "게스트로 둘러보기". Offline, the guest goes in with only the ticked time kept on the device, and the account is
    /// made on the first connection (m1.md decision 16). The same happens while the core cannot make guest accounts
    /// yet, so the calendar is usable before the server part lands.
    public func continueAsGuest(isOnline: Bool) async -> Outcome? {
        guard let ageConfirmedAt, busy == nil else { return nil }
        guard isOnline else {
            state.pendingGuestAgeConfirmedAt = ageConfirmedAt
            return .permissions
        }
        busy = .guest
        defer { busy = nil }
        do {
            _ = try await core.signInGuest(
                account: NewAccount(displayName: Self.guestName, ageConfirmedAt: ageConfirmedAt))
            state.pendingGuestAgeConfirmedAt = nil
            return .permissions
        } catch CoreError.Network(_), CoreError.NotImplemented(_) {
            state.pendingGuestAgeConfirmedAt = ageConfirmedAt
            return .permissions
        } catch {
            notify(Strings.signInFailed)
            return nil
        }
    }

    /// The account name a guest is made with (m1.md decision 16). It is data the server keeps, not interface text, so it
    /// does not follow the device language.
    static let guestName = "게스트"

    /// The provider's name, or a plain stand-in when it gives none (Apple sends the name on the first authorization
    /// only, and a person may clear it). The server takes 1–40 characters.
    static func displayName(_ name: String?, provider: Provider) -> String {
        let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = provider == .kakao ? "카카오 사용자" : "Apple 사용자"
        return String((trimmed.isEmpty ? fallback : trimmed).prefix(40))
    }

    static func outcome(of result: AuthResult) -> Outcome {
        switch result {
        case .registered(_, let newAccount, let recovery):
            // An existing account with recovery material on the server: offer its backup (protocol §13).
            return !newAccount && recovery ? .restore(.offer) : .permissions
        case .replaceRequired:
            return .restore(.deviceChange)
        }
    }
}

/// A guest who started offline gets their account on a later launch with a connection (m1.md decision 16).
public enum PendingGuest {
    public static func complete(core: any CoreProtocol, state: OnboardingState = OnboardingState()) async {
        guard let ageConfirmedAt = state.pendingGuestAgeConfirmedAt, core.session() == nil else { return }
        do {
            _ = try await core.signInGuest(
                account: NewAccount(displayName: SignInModel.guestName, ageConfirmedAt: ageConfirmedAt))
            state.pendingGuestAgeConfirmedAt = nil
        } catch {
            // Kept for the next launch; the calendar works without an account meanwhile.
        }
    }
}
