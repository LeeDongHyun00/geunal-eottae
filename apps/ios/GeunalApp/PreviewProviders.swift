#if DEBUG
    import FeatureAccount
    import Foundation

    /// The provider sheets stand-in that goes with the preview core: Kakao and Apple "sign in" at once, so the UI tests
    /// reach the screens after a sign-in without a Kakao app key or an Apple ID on the simulator.
    @MainActor
    final class PreviewProviderSignIn: ProviderSignIn {
        func kakao(providerNonce: String) async throws -> ProviderCredential {
            ProviderCredential(idToken: "preview-kakao-token", displayName: "하린")
        }

        func apple(providerNonce: String) async throws -> ProviderCredential {
            ProviderCredential(idToken: "preview-apple-token", displayName: "하린", authorizationCode: "preview-code")
        }
    }
#endif
