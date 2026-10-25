import CoreBridge
import XCTest

final class StoreDirectoryTests: XCTestCase {
    func testCreatesADirectoryUnderApplicationSupportThatBackupsSkip() throws {
        let directory = try StoreDirectory.url()

        // `path()` percent-encodes the space in "Application Support"; the file system wants the plain path.
        let path = directory.path(percentEncoded: false)
        var isDirectory: ObjCBool = false
        XCTAssertTrue(FileManager.default.fileExists(atPath: path, isDirectory: &isDirectory))
        XCTAssertTrue(isDirectory.boolValue)
        XCTAssertTrue(path.contains("/Application Support/"))
        let values = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    func testReturnsTheSameDirectoryEveryTime() throws {
        XCTAssertEqual(try StoreDirectory.url(), try StoreDirectory.url())
    }
}
