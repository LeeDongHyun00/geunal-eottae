import Foundation

/// Why `StoreDirectory` gave no directory.
public enum StoreDirectoryError: Error, Equatable {
    /// The App Group container did not resolve: the bundle names no group, or this build is not entitled to it.
    case noGroupContainer
}

/// Where the core keeps its SQLCipher database and lock file: the App Group container (docs/parts/ios.md §2).
public enum StoreDirectory {
    /// Creates the directory if needed and returns the one to open this run. The database is protected until the
    /// first unlock, like the keys in the keychain, and left out of backups: its key never leaves the device, so a
    /// restored copy could not be opened anyway and would only make the core refuse to start. `applicationSupport`
    /// stands in for the app's own directory in tests, which also pass their own `groupContainer`.
    ///
    /// A missing container throws instead of falling back to the app's own directory. A build that lost its
    /// entitlement would find no database there, read as a new installation, and `FreshInstall` would wipe the keys
    /// of the store that still sits in the group; throwing first leaves the keys alone and shows the start failure
    /// screen until a fixed build arrives.
    ///
    /// The directory returned is where the store is, so `FreshInstall` wipes the keys only when neither place has one:
    /// a store that could not move into the group opens where it was, and the next launch tries the move again.
    public static func url(
        groupContainer: URL? = AppGroup.container(), applicationSupport: URL? = nil,
        fileManager: FileManager = .default
    ) throws -> URL {
        guard let groupContainer else { throw StoreDirectoryError.noGroupContainer }
        let support =
            try applicationSupport
            ?? fileManager.url(
                for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let own = support.appendingPathComponent("core", isDirectory: true)
        let group = groupContainer.appendingPathComponent("core", isDirectory: true)
        var directory = group
        do {
            try move(from: own, to: group, fileManager: fileManager)
        } catch {
            directory = own
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
