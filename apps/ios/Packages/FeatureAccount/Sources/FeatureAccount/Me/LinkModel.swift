import Foundation
import GeunalCore
import Observation

/// "카카오·Apple 연결" on a guest's card (m1.md screen 12): signing in with a provider links it to the account, after
/// which the account comes back with that provider and the guest comeback with the code alone closes (screen 23).
@MainActor
@Observable
public final class LinkModel {
    public private(set) var isWorking = false
    public private(set) var linked: [Provider]?

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let providers: any ProviderSignIn
    @ObservationIgnored private let notify: @MainActor (String) -> Void

    public init(core: any CoreProtocol, providers: any ProviderSignIn, notify: @escaping @MainActor (String) -> Void) {
        self.core = core
        self.providers = providers
        self.notify = notify
    }

    /// `true` once the provider is linked.
    public func link(_ provider: Provider, online: Bool) async -> Bool {
        guard !isWorking else { return false }
        guard online else {
            notify(String(localized: "연결되면 할 수 있어요", bundle: .module))
            return false
        }
        isWorking = true
        defer { isWorking = false }
        do {
            let nonce = try await core.signInNonce()
            switch provider {
            case .kakao:
                let credential = try await providers.kakao(providerNonce: nonce.providerNonce)
                linked = try await core.linkKakao(nonce: nonce.nonce, idToken: credential.idToken)
            case .apple:
                let credential = try await providers.apple(providerNonce: nonce.providerNonce)
                linked = try await core.linkApple(nonce: nonce.nonce, idToken: credential.idToken)
            }
            notify(String(localized: "연결했어요", bundle: .module))
            return true
        } catch ProviderSignInError.cancelled {
            return false
        } catch CoreError.Api(409, "already_linked", _) {
            notify(String(localized: "이미 연결한 계정이에요", bundle: .module))
        } catch CoreError.Api(409, _, _) {
            // `account_exists`: the provider account belongs to another account (protocol §11).
            notify(String(localized: "이미 다른 계정에 연결된 카카오·Apple 계정이에요", bundle: .module))
        } catch {
            notify(Strings.somethingFailed)
        }
        return false
    }
}
