import Foundation

/// Which build this is. M1 ships to testers as an alpha, and only alpha builds offer the test statistics consent (m1.md
/// screen 3, decision 10; protocol §14). The value comes from the `GeunalBuildChannel` Info.plist key, which the build
/// settings fill in, so a public build turns it off without a code change.
public enum BuildChannel: String, Sendable {
    case alpha
    case `public`

    public static var current: BuildChannel {
        let value = Bundle.main.object(forInfoDictionaryKey: "GeunalBuildChannel") as? String
        return value.flatMap(BuildChannel.init(rawValue:)) ?? .public
    }
}
