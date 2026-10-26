import Foundation

/// What a Kakao or Apple sign-in gives the app: the OIDC token the core sends to the server, and the name the account is
/// made with when it is new.
public struct ProviderCredential: Equatable, Sendable {
    public var idToken: String
    /// The provider's name for the person, when it has one; the account gets a fallback otherwise.
    public var displayName: String?
    /// Apple only: the one-time code `DELETE /me` needs to revoke Apple's tokens (protocol §13). Sign-in does not send
    /// it.
    public var authorizationCode: String?

    public init(idToken: String, displayName: String? = nil, authorizationCode: String? = nil) {
        self.idToken = idToken
        self.displayName = displayName
        self.authorizationCode = authorizationCode
    }
}

public enum ProviderSignInError: Error, Equatable, Sendable {
    /// The person closed the provider's sheet. Not an error to show.
    case cancelled
    /// The provider is not set up in this build (no Kakao app key yet), or the token came back without an ID token.
    case unavailable
    case failed(String)
}

/// The two provider SDKs behind one seam, so the sign-in model can be tested without them. Both take the core's
/// `providerNonce` as the OIDC nonce, which binds the token to this device's key (protocol §13 "nonce 묶기").
@MainActor
public protocol ProviderSignIn: AnyObject {
    func kakao(providerNonce: String) async throws -> ProviderCredential
    func apple(providerNonce: String) async throws -> ProviderCredential
}
