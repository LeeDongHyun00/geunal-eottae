import Foundation
import GeunalCore
import Observation

/// The people screen (m1.md screen 11): the birthdays of the next 30 days, everyone by name, and the name search over
/// both. Everything is on the device, so it works offline like the calendar.
@MainActor
@Observable
public final class PeopleModel {
    public static let upcomingDays: UInt16 = 30

    public private(set) var people: [Person] = []
    public private(set) var upcoming: [Person] = []
    public private(set) var loaded = false
    public var query = ""

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let now: @Sendable () -> Date

    public init(core: any CoreProtocol, now: @escaping @Sendable () -> Date = Date.init) {
        self.core = core
        self.now = now
    }

    public var today: String { KoreanCalendar.day(of: now()) }

    /// "아직 아무도 없어요" once the core has answered with nobody.
    public var isEmpty: Bool { loaded && people.isEmpty }

    public var matchingUpcoming: [Person] { upcoming.filter(matches) }
    public var matchingPeople: [Person] { people.filter(matches) }

    public func load() async {
        let core = self.core
        let days = Self.upcomingDays
        let found = try? await offMain { (try core.people(), try core.upcomingBirthdays(days: days)) }
        people = found?.0 ?? []
        upcoming = found?.1 ?? []
        loaded = true
    }

    public func person(_ id: String) -> Person? {
        people.first { $0.id == id }
    }

    /// Any part of the name, ignoring case and width: "민" finds 민수 and 김민지.
    private func matches(_ person: Person) -> Bool {
        let query = self.query.trimmingCharacters(in: .whitespaces)
        return query.isEmpty || person.name.localizedStandardContains(query)
    }
}
