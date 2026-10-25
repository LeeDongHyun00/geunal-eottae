import CoreBridge
import XCTest

final class StoreDirectoryTests: XCTestCase {
    func testCreatesADirectoryUnderApplicationSupportThatBackupsSkip() throws {
        let directory = try StoreDirectory.url()

        var isDirectory: ObjCBool = false
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.path(), isDirectory: &isDirectory))
        XCTAssertTrue(isDirectory.boolValue)
        XCTAssertTrue(directory.path().contains("/Application Support/"))
        let values = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    func testReturnsTheSameDirectoryEveryTime() throws {
        XCTAssertEqual(try StoreDirectory.url(), try StoreDirectory.url())
    }
}
