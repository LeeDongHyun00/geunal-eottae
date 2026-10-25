import DesignSystem
import XCTest

final class DesignTokensTests: XCTestCase {
    func testHasTwelveGroupColorsWithDistinctNames() {
        XCTAssertEqual(GroupColor.allCases.count, 12)
        XCTAssertEqual(Set(GroupColor.allCases.map(\.name)).count, 12)
    }

    func testEveryGroupColorHasFiveTints() {
        for color in GroupColor.allCases {
            for level in 1...5 {
                _ = color.tint(level)
            }
            _ = color.representative
        }
    }

    // docs/design/README.md §2.2: in a group of ten, one to three free members are level 1 and nine are level 4.
    func testMapsTheFreeCountToATintLevel() {
        XCTAssertEqual(GroupColor.level(free: 0, of: 10), 0)
        XCTAssertEqual(GroupColor.level(free: 1, of: 10), 1)
        XCTAssertEqual(GroupColor.level(free: 3, of: 10), 1)
        XCTAssertEqual(GroupColor.level(free: 4, of: 10), 2)
        XCTAssertEqual(GroupColor.level(free: 9, of: 10), 4)
        XCTAssertEqual(GroupColor.level(free: 10, of: 10), 5)
        XCTAssertEqual(GroupColor.level(free: 1, of: 2), 1)
        XCTAssertEqual(GroupColor.level(free: 2, of: 2), 5)
        XCTAssertEqual(GroupColor.level(free: 1, of: 1), 5)
    }

    func testTypeScaleFollowsTheTokens() {
        XCTAssertEqual(TypeStyle.largeTitle.size, 32)
        XCTAssertEqual(TypeStyle.body.weight, .regular)
        XCTAssertEqual(TypeStyle.bodyStrong.weight, .semibold)
        XCTAssertEqual(TypeStyle.micro.size, 11)
        XCTAssertEqual(Space.m, 16)
        XCTAssertEqual(Radius.card, 20)
        XCTAssertEqual(Radius.pill, 999)
    }
}
