import Foundation

/// Where the core keeps its SQLCipher database and lock file.
public enum StoreDirectory {
    /// Creates the directory if needed and returns it. The database is protected until the first unlock, like the keys
    /// in the keychain, and left out of backups: its key never leaves the device, so a restored copy could not be
    /// opened anyway and would only make the core refuse to start.
    ///
    /// The App Group container that the notification extension shares (docs/parts/ios.md §2) needs the entitlement and
    /// signing, which come with M1; until then the app's own Application Support directory holds the store.
    public static func url(fileManager: FileManager = .default) throws -> URL {
        let base = try fileManager.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var directory = base.appendingPathComponent("core", isDirectory: true)
        try fileManager.createDirectory(
            at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        return directory
    }
}
