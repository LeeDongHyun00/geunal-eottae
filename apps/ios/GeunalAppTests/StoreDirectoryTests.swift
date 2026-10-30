import CoreBridge
import GeunalCore
import XCTest

final class StoreDirectoryTests: XCTestCase {
    private func temporaryDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory
    }

    func testKeepsTheStoreInTheAppGroupThatBackupsSkip() throws {
        let group = try XCTUnwrap(AppGroup.container(), "the app is entitled to its App Group")
        let directory = try StoreDirectory.url()

        // `path()` percent-encodes spaces; the file system wants the plain path.
        let path = directory.path(percentEncoded: false)
        var isDirectory: ObjCBool = false
        XCTAssertTrue(FileManager.default.fileExists(atPath: path, isDirectory: &isDirectory))
        XCTAssertTrue(isDirectory.boolValue)
        XCTAssertTrue(path.hasPrefix(group.path(percentEncoded: false)))
        let values = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    func testUsesApplicationSupportWithoutAGroup() throws {
        let directory = try StoreDirectory.url(groupContainer: nil)

        XCTAssertTrue(directory.path(percentEncoded: false).contains("/Application Support/"))
    }

    func testReturnsTheSameDirectoryEveryTime() throws {
        XCTAssertEqual(try StoreDirectory.url(), try StoreDirectory.url())
    }

    func testMovesAnEarlierBuildsStoreIntoTheGroup() throws {
        let support = try temporaryDirectory()
        let group = try temporaryDirectory()
        let old = support.appendingPathComponent("core", isDirectory: true)
        try FileManager.default.createDirectory(at: old, withIntermediateDirectories: true)
        try Data("db".utf8).write(to: old.appendingPathComponent(FreshInstall.databaseFile))
        try Data("wal".utf8).write(to: old.appendingPathComponent(FreshInstall.databaseFile + "-wal"))
        // An earlier launch of this build may have made the group's directory already.
        try FileManager.default.createDirectory(
            at: group.appendingPathComponent("core", isDirectory: true), withIntermediateDirectories: true)

        let directory = try StoreDirectory.url(groupContainer: group, applicationSupport: support)

        XCTAssertTrue(FreshInstall.hasStore(in: directory))
        XCTAssertEqual(
            try Data(contentsOf: directory.appendingPathComponent(FreshInstall.databaseFile + "-wal")),
            Data("wal".utf8))
        XCTAssertFalse(FreshInstall.hasStore(in: old))
    }

    /// QA: a store that moves must open with the same key, or the core would set it aside as unreadable and start
    /// empty. The key stays where it was: the keychain items keep their service and access group.
    func testTheMovedStoreOpensWithTheSameKey() throws {
        let support = try temporaryDirectory()
        let group = try temporaryDirectory()
        let secrets = MemorySecretStore()
        let old = support.appendingPathComponent("core", isDirectory: true)
        try FileManager.default.createDirectory(at: old, withIntermediateDirectories: true)
        try {
            let core = try Core.open(
                storeDir: old.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
                listener: ChangeStreamListener(), secrets: secrets)
            let calendarId = try XCTUnwrap(core.calendars().first { $0.kind == .own }?.id)
            _ = try core.addEvent(
                input: EventInput(
                    calendarId: calendarId, title: "이사 전 일정", allDay: false,
                    start: DayTime(day: "2026-11-02", minute: 540), end: DayTime(day: "2026-11-02", minute: 600),
                    recurrence: nil, alert: .inherit, note: nil),
                fromSuggestion: false)
        }()

        let directory = try StoreDirectory.url(groupContainer: group, applicationSupport: support)
        let moved = try Core.open(
            storeDir: directory.path(percentEncoded: false), apiBase: "http://127.0.0.1:8080",
            listener: ChangeStreamListener(), secrets: secrets)

        XCTAssertEqual(try moved.calendars().first { $0.kind == .own }?.eventCount, 1)
    }

    func testLeavesTheGroupsStoreAloneWhenBothHaveOne() throws {
        let support = try temporaryDirectory()
        let group = try temporaryDirectory()
        for (base, content) in [(support, "old"), (group, "new")] {
            let core = base.appendingPathComponent("core", isDirectory: true)
            try FileManager.default.createDirectory(at: core, withIntermediateDirectories: true)
            try Data(content.utf8).write(to: core.appendingPathComponent(FreshInstall.databaseFile))
        }

        let directory = try StoreDirectory.url(groupContainer: group, applicationSupport: support)

        XCTAssertEqual(
            try Data(contentsOf: directory.appendingPathComponent(FreshInstall.databaseFile)), Data("new".utf8))
    }
}
