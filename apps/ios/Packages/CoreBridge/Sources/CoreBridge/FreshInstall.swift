import Foundation

/// iOS keeps keychain items when the app is deleted, but not the app's files or defaults. A reinstalled app would find
/// the previous install's session, device key, recovery entropy and master key next to an empty store, and anyone who
/// gets the phone afterwards would inherit them. So the first launch of an install wipes them before the core opens.
public enum FreshInstall {
    /// Lives in the standard defaults, which go away with the app; the keychain does not.
    public static let markerKey = "app.geunal.installed"

    /// Wipes the keychain items when `defaults` carries no marker from an earlier launch, then sets the marker.
    /// Returns whether it wiped. A device restored from a backup brings the defaults along but not these items, which
    /// are this-device-only, so the marker never hides a wipe that was needed.
    @discardableResult
    public static func wipeLeftovers(defaults: UserDefaults = .standard, keychain: KeychainSecretStore) -> Bool {
        guard !defaults.bool(forKey: markerKey) else { return false }
        keychain.deleteAll()
        defaults.set(true, forKey: markerKey)
        return true
    }
}
