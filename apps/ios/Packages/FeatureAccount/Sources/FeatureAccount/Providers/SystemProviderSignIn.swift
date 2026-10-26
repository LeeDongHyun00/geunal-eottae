import Foundation

/// The real providers: Kakao through its SDK, Apple through AuthenticationServices.
@MainActor
public final class SystemProviderSignIn: ProviderSignIn {
    private let kakaoSignIn = KakaoSignIn()
    private let appleSignIn = AppleSignIn()

    public init() {}

    public func kakao(providerNonce: String) async throws -> ProviderCredential {
        try await kakaoSignIn.signIn(providerNonce: providerNonce)
    }

    public func apple(providerNonce: String) async throws -> ProviderCredential {
        try await appleSignIn.signIn(providerNonce: providerNonce)
    }
}
