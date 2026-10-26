import Foundation
import GeunalCore
import Observation

/// The week view (m1.md screen 7): one page per week from Sunday, swiped sideways.
@MainActor
@Observable
public final class WeekModel {
    /// The pager's weeks by their Sunday, ten years either side of this week.
    public let weeks: [String]
    public var visibleWeek: String?
    public private(set) var today: String
    public private(set) var pages: [String: [WeekDay]] = [:]

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let now: @Sendable () -> Date

    public init(core: any CoreProtocol, now: @escaping @Sendable () -> Date = Date.init, years: Int = 10) {
        self.core = core
        self.now = now
        let today = KoreanCalendar.day(of: now())
        let current = KoreanCalendar.weekStart(of: today) ?? today
        self.today = today
        let count = 53 * years
        weeks = (-count...count).compactMap { KoreanCalendar.adding(days: 7 * $0, to: current) }
        visibleWeek = current
    }

    public var currentWeek: String { KoreanCalendar.weekStart(of: today) ?? today }

    public func load(_ week: String) async {
        guard pages[week] == nil else { return }
        await read(week)
    }

    public func reload() async {
        today = KoreanCalendar.day(of: now())
        let visible = visibleWeek ?? currentWeek
        let keep = Set([-7, 0, 7].compactMap { KoreanCalendar.adding(days: $0, to: visible) })
        pages = pages.filter { keep.contains($0.key) }
        for week in keep.sorted() {
            await read(week)
        }
    }

    public func showToday() {
        today = KoreanCalendar.day(of: now())
        visibleWeek = currentWeek
    }

    /// The week a day of the month view falls in, for switching views without losing the place.
    public func show(day: String) {
        visibleWeek = KoreanCalendar.weekStart(of: day) ?? visibleWeek
    }

    /// Minutes since midnight now, for the red line, while today is on the page.
    public func nowMinute(on week: String) -> Int? {
        let date = now()
        let day = KoreanCalendar.day(of: date)
        guard let offset = KoreanCalendar.days(from: week, to: day), (0..<7).contains(offset),
            let midnight = KoreanCalendar.start(of: day)
        else { return nil }
        return Int(date.timeIntervalSince(midnight) / 60)
    }

    private func read(_ week: String) async {
        let core = self.core
        guard let days = try? await offMain({ try core.calendarWeek(startDay: week) }) else { return }
        pages[week] = days
    }
}
