import FeatureCalendar
import Foundation
import XCTest

/// A scheduled alert names one instant, wherever the phone is when it comes due (docs/parts/ios.md §5).
final class AlertTriggerTests: XCTestCase {
    /// 2026-10-30 08:00:00 GMT, which is 17:00 in Seoul.
    private let evening = Date(timeIntervalSince1970: 1_793_347_200)

    func testTheComponentsCarryTheirTimeZoneAndCalendar() {
        let components = SystemAlertCenter.components(of: evening)

        XCTAssertEqual(components.timeZone, TimeZone.gmt)
        XCTAssertEqual(components.calendar?.identifier, Calendar.Identifier.gregorian)
        XCTAssertEqual(components.year, 2026)
        XCTAssertEqual(components.month, 10)
        XCTAssertEqual(components.day, 30)
        XCTAssertEqual(components.hour, 8)
        XCTAssertEqual(components.minute, 0)
        XCTAssertEqual(components.second, 0)
    }

    func testTheComponentsReadBackAsTheSameInstant() {
        XCTAssertEqual(SystemAlertCenter.instant(of: SystemAlertCenter.components(of: evening)), evening)
    }

    /// What the system does on a phone that has moved: it reads the components in the zone they carry, not its own.
    func testTheInstantDoesNotMoveWithThePhonesTimeZone() throws {
        let components = SystemAlertCenter.components(of: evening)

        for identifier in ["Asia/Seoul", "America/Los_Angeles", "Europe/Paris"] {
            var local = Calendar(identifier: .gregorian)
            local.timeZone = try XCTUnwrap(TimeZone(identifier: identifier))
            XCTAssertEqual(local.date(from: components), evening, identifier)
        }
    }

    /// The hour daylight saving skips in a local zone (02:30 on 2027-03-14 in Los Angeles does not exist) is an
    /// ordinary hour in GMT.
    func testAnInstantNextToADaylightSavingChangeSurvives() throws {
        var losAngeles = Calendar(identifier: .gregorian)
        losAngeles.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let beforeTheChange = try XCTUnwrap(
            losAngeles.date(from: DateComponents(year: 2027, month: 3, day: 14, hour: 1, minute: 59)))
        let afterTheChange = beforeTheChange.addingTimeInterval(60)

        for instant in [beforeTheChange, afterTheChange] {
            XCTAssertEqual(SystemAlertCenter.instant(of: SystemAlertCenter.components(of: instant)), instant)
        }
    }

    /// An earlier build scheduled wall-clock components without a zone. They equal no planned alert, so the refresh
    /// cancels and schedules them again as instants.
    func testComponentsWithoutATimeZoneAreReplaced() {
        let wallClock = DateComponents(year: 2026, month: 10, day: 30, hour: 17, minute: 0, second: 0)

        XCTAssertEqual(SystemAlertCenter.instant(of: wallClock), Date.distantPast)
    }
}
