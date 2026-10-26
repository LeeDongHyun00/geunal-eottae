@preconcurrency import AuthenticationServices
import UIKit

/// Sign in with Apple through `ASAuthorizationController`. The request carries the core's provider nonce as is: the
/// server compares it with the token's `nonce` claim, so the app must not hash it again (protocol §13).
@MainActor
final class AppleSignIn: NSObject {
    private var continuation: CheckedContinuation<ProviderCredential, any Error>?
    private var controller: ASAuthorizationController?

    func signIn(providerNonce: String) async throws -> ProviderCredential {
        // A second tap while the sheet is up would orphan the first continuation.
        guard continuation == nil else { throw ProviderSignInError.failed("an Apple sign-in is already running") }
        let request = ASAuthorizationAppleIDProvider().createRequest()
        // The name is only for a new account; Apple sends it on the first authorization alone.
        request.requestedScopes = [.fullName]
        request.nonce = providerNonce
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        self.controller = controller
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            controller.performRequests()
        }
    }

    private func finish(_ result: Result<ProviderCredential, any Error>) {
        let continuation = self.continuation
        self.continuation = nil
        controller = nil
        continuation?.resume(with: result)
    }

    private static func credential(from authorization: ASAuthorization) -> Result<ProviderCredential, any Error> {
        guard let apple = authorization.credential as? ASAuthorizationAppleIDCredential,
            let tokenData = apple.identityToken, let idToken = String(data: tokenData, encoding: .utf8)
        else {
            return .failure(ProviderSignInError.unavailable)
        }
        let name = apple.fullName.map { PersonNameComponentsFormatter.localizedString(from: $0, style: .default) }
        let code = apple.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        return .success(ProviderCredential(idToken: idToken, displayName: name, authorizationCode: code))
    }
}

extension AppleSignIn: ASAuthorizationControllerDelegate {
    func authorizationController(
        controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        finish(Self.credential(from: authorization))
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: any Error) {
        if let error = error as? ASAuthorizationError, error.code == .canceled {
            finish(.failure(ProviderSignInError.cancelled))
        } else {
            finish(.failure(ProviderSignInError.failed(String(describing: type(of: error)))))
        }
    }
}

extension AppleSignIn: ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first
        return window ?? ASPresentationAnchor()
    }
}
