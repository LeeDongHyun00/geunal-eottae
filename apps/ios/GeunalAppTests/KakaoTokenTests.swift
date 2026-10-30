import FeatureAccount
import XCTest

/// QA D12: the Kakao SDK's tokens stay in memory, out of the standard defaults that iCloud backups and Quick Start carry
/// to another phone. The check lives in FeatureAccount, which links the SDK.
@MainActor
final class KakaoTokenTests: XCTestCase {
    func testKeepsKakaoTokensInMemoryOnly() {
        KakaoSetup.configure()

        XCTAssertTrue(KakaoSetup.keepsTokensInMemory)
    }
}
