import Foundation

/// iOS keeps keychain items when the app is deleted, but not the app's files. A reinstalled app would find the previous
/// install's session, device key, recovery entropy and master key next to an empty store, and anyone who gets the phone
/// afterwards would inherit them. So a launch that finds no store wipes them before the core opens.
///
/// The store decides, not a flag in the defaults: the defaults travel with an iCloud backup or Quick Start to a new
/// phone, while the store is left out of backups (`StoreDirectory`) and goes away with the app. A launch with a store is
/// the same installation, an upgrade from an earlier build included, and keeps its keys.
public enum FreshInstall {
    /// The core's database file (core/geunal-store `DB_FILE`).
    public static let databaseFile = "geunal.db"

    /// Whether `directory` holds the core's database. Readable before the first unlock too, unlike the defaults.
    public static func hasStore(in directory: URL, fileManager: FileManager = .default) -> Bool {
        fileManager.fileExists(atPath: directory.appendingPathComponent(databaseFile).path(percentEncoded: false))
    }

    /// Wipes the keychain items when `storeDirectory` has no database yet, and returns whether it did, so the caller can
    /// also forget what the defaults brought from another phone.
    @discardableResult
    public static func wipeLeftovers(
        storeDirectory: URL, keychain: KeychainSecretStore, fileManager: FileManager = .default
    ) -> Bool {
        guard !hasStore(in: storeDirectory, fileManager: fileManager) else { return false }
        keychain.deleteAll()
        return true
    }
}
