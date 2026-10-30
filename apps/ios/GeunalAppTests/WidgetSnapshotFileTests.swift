import Foundation
import HomeWidget
import XCTest

@testable import GeunalApp

/// The widget snapshot's file in the App Group (docs/security/app-review.md §2 "위젯 스냅샷"), and the words the
/// widgets put on it.
final class WidgetSnapshotFileTests: XCTestCase {
    private func container() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory
    }

    func testWritesOnlyAChangedSnapshotOutOfBackups() throws {
        let container = try container()
        let first = Data(#"{"v":1}"#.utf8)

        XCTAssertTrue(WidgetSnapshotFile.write(first, in: container))
        XCTAssertFalse(WidgetSnapshotFile.write(first, in: container))
        XCTAssertTrue(WidgetSnapshotFile.write(Data(#"{"v":1,"day":"2026-10-25"}"#.utf8), in: container))

        let values = try WidgetFile.directory(in: container).resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    func testRemovesTheSnapshotOnASignOut() throws {
        let container = try container()
        XCTAssertTrue(WidgetSnapshotFile.write(Data("{}".utf8), in: container))

        XCTAssertTrue(WidgetSnapshotFile.remove(in: container))
        XCTAssertFalse(WidgetSnapshotFile.remove(in: container))
        XCTAssertFalse(FileManager.default.fileExists(atPath: WidgetFile.snapshot(in: container).path))
    }

    func testWordsTheDayAndTheEventsTimes() {
        XCTAssertEqual(WidgetText.heading("2026-10-25"), "10월 25일 일요일")
        XCTAssertEqual(WidgetText.weekday("2026-10-31"), "토요일")
        XCTAssertEqual(WidgetText.dayNumber("2026-10-05"), "5")
        XCTAssertEqual(
            WidgetText.time(
                WidgetSnapshot.Event(title: "가족 · 저녁", color: "blossom", allDay: false, start: "18:00", end: "20:00")),
            "18:00–20:00")
        XCTAssertEqual(
            WidgetText.time(WidgetSnapshot.Event(title: "연차", color: nil, allDay: true, start: nil, end: nil)), "종일")
    }
}
