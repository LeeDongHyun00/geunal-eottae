import Foundation
import HomeWidget

/// The widgets' snapshot in the App Group container (docs/security/app-review.md §2 "위젯 스냅샷"): today's and
/// tomorrow's titles in plain text, so the file is protected until the first unlock and its directory stays out of
/// backups. A sign-out or a deleted account takes it away.
enum WidgetSnapshotFile {
    /// Writes `data` unless the file already holds it, and says whether it changed, so the widgets reload only then.
    static func write(_ data: Data, in container: URL) -> Bool {
        let file = WidgetFile.snapshot(in: container)
        if (try? Data(contentsOf: file)) == data {
            return false
        }
        var directory = WidgetFile.directory(in: container)
        do {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            return false
        }
    }

    /// Whether there was one to remove.
    static func remove(in container: URL) -> Bool {
        (try? FileManager.default.removeItem(at: WidgetFile.snapshot(in: container))) != nil
    }
}
