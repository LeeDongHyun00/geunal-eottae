import Foundation

/// The App Group the app shares with its widget, and from M3 with the notification extension (docs/parts/ios.md §2).
/// Its identifier comes from the build setting `GEUNAL_APP_GROUP` through the Info.plist key `GeunalAppGroup`, so the
/// entitlements and the code name the same group.
public enum AppGroup {
    /// `nil` when the bundle names no group or the build is not entitled to it.
    public static func container(bundle: Bundle = .main, fileManager: FileManager = .default) -> URL? {
        guard let identifier = bundle.object(forInfoDictionaryKey: "GeunalAppGroup") as? String, !identifier.isEmpty
        else { return nil }
        return fileManager.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }
}
