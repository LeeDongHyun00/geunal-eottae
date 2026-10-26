import Foundation
@preconcurrency import KakaoSDKAuth
@preconcurrency import KakaoSDKCommon
@preconcurrency import KakaoSDKUser

/// Kakao Login through the Kakao SDK, KakaoTalk first and the web login when KakaoTalk is not installed. The SDK sends
/// the authorization code with PKCE (S256) on both paths. The app needs only the OIDC ID token: right after reading the
/// nickname it logs out of the SDK, which deletes Kakao's access and refresh tokens from the standard defaults.
@MainActor
final class KakaoSignIn {
    func signIn(providerNonce: String) async throws -> ProviderCredential {
        guard KakaoSetup.isConfigured else { throw ProviderSignInError.unavailable }
        let idToken: String = try await withCheckedThrowingContinuation { continuation in
            // Sendable closures, so none inherits the main actor if the SDK answers on another queue.
            let completion: @Sendable (OAuthToken?, (any Error)?) -> Void = { token, error in
                if let error {
                    continuation.resume(throwing: Self.map(error))
                } else if let idToken = token?.idToken {
                    continuation.resume(returning: idToken)
                } else {
                    // An app without OpenID Connect turned on in the Kakao console gets no ID token.
                    continuation.resume(throwing: ProviderSignInError.unavailable)
                }
            }
            if UserApi.isKakaoTalkLoginAvailable() {
                UserApi.shared.loginWithKakaoTalk(nonce: providerNonce, completion: completion)
            } else {
                UserApi.shared.loginWithKakaoAccount(
                    prompts: nil, loginHint: nil, nonce: providerNonce, completion: completion)
            }
        }
        let nickname = await nickname()
        UserApi.shared.logout { @Sendable _ in }
        return ProviderCredential(idToken: idToken, displayName: nickname)
    }

    private func nickname() async -> String? {
        await withCheckedContinuation { continuation in
            UserApi.shared.me { @Sendable user, _ in
                continuation.resume(returning: user?.kakaoAccount?.profile?.nickname)
            }
        }
    }

    private nonisolated static func map(_ error: any Error) -> ProviderSignInError {
        if case SdkError.ClientFailed(reason: .Cancelled, _) = error {
            return .cancelled
        }
        return .failed(String(describing: type(of: error)))
    }
}

/// The Kakao SDK's app-wide setup: the native app key from the `KakaoAppKey` Info.plist entry (filled from
/// `apps/ios/Config/Secrets.xcconfig`, which git ignores) and the KakaoTalk redirect.
public enum KakaoSetup {
    static var appKey: String? {
        let key = Bundle.main.object(forInfoDictionaryKey: "KakaoAppKey") as? String
        return key.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Without a key the Kakao button stays, and pressing it says the sign-in did not work.
    static var isConfigured: Bool { appKey != nil }

    @MainActor
    public static func configure() {
        guard let appKey else { return }
        KakaoSDK.initSDK(appKey: appKey, loggingEnable: false)
    }

    /// Hands the KakaoTalk login redirect to the SDK; `false` for any other URL.
    @MainActor
    public static func handle(_ url: URL) -> Bool {
        guard isConfigured, AuthApi.isKakaoTalkLoginUrl(url) else { return false }
        return AuthController.handleOpenUrl(url: url)
    }
}
