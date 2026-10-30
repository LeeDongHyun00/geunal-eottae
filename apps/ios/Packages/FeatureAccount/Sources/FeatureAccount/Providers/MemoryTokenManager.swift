import Foundation
@preconcurrency import KakaoSDKAuth

/// The Kakao SDK's OAuth tokens, in memory only. The SDK's own `TokenManager` writes them to the standard defaults,
/// which iCloud backups and Quick Start carry to another phone, while the app needs the ID token for one sign-in and
/// logs out right after (QA D12). The SDK reads `AUTH.tokenManager` on every call, from any queue.
final class MemoryTokenManager: TokenManagable, @unchecked Sendable {
    private let lock = NSLock()
    private var token: OAuthToken?

    func setToken(_ token: OAuthToken?) {
        lock.withLock { self.token = token }
    }

    func getToken() -> OAuthToken? {
        lock.withLock { token }
    }

    func deleteToken() {
        lock.withLock { token = nil }
    }
}
