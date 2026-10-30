import XCTest

@testable import GeunalApp

/// Where the app finds the API server (`AppCore.apiBase`): the environment, then Info.plist, then the build's default.
final class APIBaseTests: XCTestCase {
    private let localStack = "http://127.0.0.1:8080"
    private let alpha = "https://alpha.example"

    func testTheEnvironmentComesBeforeInfoPlist() {
        // A scheme or a UI test points the simulator at another server without building again.
        let base = AppCore.resolveAPIBase(
            environment: ["GEUNAL_API_BASE": "http://127.0.0.1:9090"], info: ["GeunalAPIBase": alpha],
            fallback: localStack)

        XCTAssertEqual(base, "http://127.0.0.1:9090")
    }

    func testInfoPlistComesBeforeTheDefault() {
        // A device or TestFlight build started outside Xcode has no environment, only what the build wrote.
        let base = AppCore.resolveAPIBase(environment: [:], info: ["GeunalAPIBase": alpha], fallback: localStack)

        XCTAssertEqual(base, alpha)
    }

    func testEmptyValuesCountAsUnset() {
        // `GEUNAL_API_BASE =` with no Secrets.xcconfig over it leaves an empty string in Info.plist.
        let base = AppCore.resolveAPIBase(
            environment: ["GEUNAL_API_BASE": ""], info: ["GeunalAPIBase": ""], fallback: localStack)

        XCTAssertEqual(base, localStack)
    }

    func testAReleaseBuildWithoutAnAddressHasNoServer() {
        let base = AppCore.resolveAPIBase(environment: [:], info: ["GeunalAPIBase": ""], fallback: "")

        XCTAssertEqual(base, "")
    }

    func testOnlyDebugBuildsDefaultToTheLocalStack() {
        #if DEBUG
            XCTAssertEqual(AppCore.defaultAPIBase, localStack)
        #else
            XCTAssertEqual(AppCore.defaultAPIBase, "")
        #endif
    }

    func testTheBuildWritesTheSettingIntoInfoPlist() throws {
        // Empty here unless Secrets.xcconfig sets an address; either way the build must have substituted the setting.
        let value = try XCTUnwrap(Bundle.main.infoDictionary?["GeunalAPIBase"] as? String)

        XCTAssertFalse(value.contains("$("), value)
    }
}
