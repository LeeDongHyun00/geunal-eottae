import FeatureAccount
@preconcurrency import KakaoSDKAuth
import XCTest

/// QA D12: the Kakao SDK's tokens stay in memory, out of the standard defaults that iCloud backups and Quick Start carry
/// to another phone.
@MainActor
final class KakaoTokenTests: XCTestCase {
    func testKeepsKakaoTokensInMemoryOnly() {
        KakaoSetup.configure()
        let key = "com.kakao.sdk.oauth_token"
        let before = UserDefaults.standard.data(forKey: key)
        let manager = Auth.shared.tokenManager
        XCTAssertFalse(manager is KakaoSDKAuth.TokenManager)

        manager.setToken(
            OAuthToken(
                accessToken: "access", tokenType: "bearer", refreshToken: "refresh", scope: nil, scopes: nil,
                idToken: "id"))

        XCTAssertEqual(manager.getToken()?.accessToken, "access")
        XCTAssertEqual(UserDefaults.standard.data(forKey: key), before)
        manager.deleteToken()
        XCTAssertNil(manager.getToken())
    }
}
