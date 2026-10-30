import Foundation

/// Where the core keeps its SQLCipher database and lock file: the App Group container (docs/parts/ios.md §2), or the
/// app's own Application Support in a build without the group.
public enum StoreDirectory {
    /// Creates the directory if needed and returns it. The database is protected until the first unlock, like the keys
    /// in the keychain, and left out of backups: its key never leaves the device, so a restored copy could not be
    /// opened anyway and would only make the core refuse to start. `applicationSupport` stands in for the app's own
    /// directory in tests.
    public static func url(
        groupContainer: URL? = AppGroup.container(), applicationSupport: URL? = nil,
        fileManager: FileManager = .default
    ) throws -> URL {
        let support =
            try applicationSupport
            ?? fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let own = support.appendingPathComponent("core", isDirectory: true)
        var directory = own
        if let groupContainer {
            directory = groupContainer.appendingPathComponent("core", isDirectory: true)
            try move(from: own, to: directory, fileManager: fileManager)
        }
        try fileManager.createDirectory(
            at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        return directory
    }

    /// Builds before the App Group kept the store in Application Support. The first launch with the group moves it
    /// over whole, before `FreshInstall` looks: a store left behind would read as a new installation and cost the keys
    /// that open it.
    private static func move(from old: URL, to new: URL, fileManager: FileManager) throws {
        guard FreshInstall.hasStore(in: old, fileManager: fileManager),
            !FreshInstall.hasStore(in: new, fileManager: fileManager)
        else { return }
        if fileManager.fileExists(atPath: new.path(percentEncoded: false)) {
            // A directory without a database, which an earlier launch may have made.
            try fileManager.removeItem(at: new)
        }
        try fileManager.createDirectory(at: new.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fileManager.moveItem(at: old, to: new)
    }
}
