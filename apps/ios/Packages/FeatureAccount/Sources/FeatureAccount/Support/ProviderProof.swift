import Foundation
import GeunalCore

/// A fresh sign-in with the account's provider that the server checks before it deletes the account or its backups
/// (protocol §13): Kakao's ID token with the core's nonce, or Apple's one-time authorization code. Signing in this way
/// changes nothing on the device; the session stays as it was.
enum ProviderProof {
    @MainActor
    static func make(
        with provider: Provider, core: any CoreProtocol, providers: any ProviderSignIn
    ) async throws -> DeletionProof {
        let nonce = try await AccountTrace.measure("proof nonce") { try await core.signInNonce() }
        switch provider {
        case .kakao:
            let credential = try await AccountTrace.measure("proof Kakao sheet") {
                try await providers.kakao(providerNonce: nonce.providerNonce)
            }
            return .kakao(nonce: nonce.nonce, idToken: credential.idToken)
        case .apple:
            let credential = try await AccountTrace.measure("proof Apple sheet") {
                try await providers.apple(providerNonce: nonce.providerNonce)
            }
            guard let code = credential.authorizationCode else { throw ProviderSignInError.unavailable }
            return .apple(authorizationCode: code)
        }
    }
}
