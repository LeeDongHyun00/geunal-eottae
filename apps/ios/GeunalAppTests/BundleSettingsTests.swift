import XCTest

/// What App Store Connect checks in the built bundle, caught here rather than on an upload.
final class BundleSettingsTests: XCTestCase {
    func testTheAppIsForIPhoneOnly() throws {
        // A bundle that also names the iPad (2) must allow every orientation, and the app is portrait only (90474).
        let families = try XCTUnwrap(Bundle.main.infoDictionary?["UIDeviceFamily"] as? [Int])

        XCTAssertEqual(families, [1])
    }

    func testTheAppIsPortraitOnly() throws {
        let orientations = try XCTUnwrap(Bundle.main.infoDictionary?["UISupportedInterfaceOrientations"] as? [String])

        XCTAssertEqual(orientations, ["UIInterfaceOrientationPortrait"])
    }
}
