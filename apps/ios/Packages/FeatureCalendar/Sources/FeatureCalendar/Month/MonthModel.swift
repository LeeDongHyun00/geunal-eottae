import Foundation
import GeunalCore
import Observation

/// The month view (m1.md screen 4): one page per month, swiped sideways. A page reads the core when it comes into view
/// and stays until the calendar changes.
@MainActor
@Observable
public final class MonthModel {
    /// The pager's months, "YYYY-MM", ten years either side of this month.
    public let months: [String]
    /// The month on screen; the pager binds to it.
    public var visibleMonth: String?
    public private(set) var today: String
    public private(set) var pages: [String: [DayCell]] = [:]

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let now: @Sendable () -> Date

    public init(core: any CoreProtocol, now: @escaping @Sendable () -> Date = Date.init, years: Int = 10) {
        self.core = core
        self.now = now
        let today = KoreanCalendar.day(of: now())
        let current = KoreanCalendar.month(of: today)
        self.today = today
        months = (-12 * years...12 * years).compactMap { KoreanCalendar.adding(months: $0, to: current) }
        visibleMonth = current
    }

    public var currentMonth: String { KoreanCalendar.month(of: today) }

    /// The weeks of a loaded page, seven cells each.
    public func weeks(of month: String) -> [[DayCell]] {
        let cells = pages[month] ?? []
        return stride(from: 0, to: cells.count, by: 7).map { Array(cells[$0..<min($0 + 7, cells.count)]) }
    }

    /// The first-run hint under today's cell (m1.md screen 4): this month is loaded and has nothing on it yet.
    public var showsFirstRunHint: Bool {
        guard visibleMonth == currentMonth, let cells = pages[currentMonth] else { return false }
        return cells.allSatisfy { $0.events.isEmpty }
    }

    public func load(_ month: String) async {
        guard pages[month] == nil else { return }
        await read(month)
    }

    /// After a change: the pages around the visible month again, the rest when they come back into view.
    public func reload() async {
        today = KoreanCalendar.day(of: now())
        let visible = visibleMonth ?? currentMonth
        let keep = Set([-1, 0, 1].compactMap { KoreanCalendar.adding(months: $0, to: visible) })
        pages = pages.filter { keep.contains($0.key) }
        for month in keep.sorted() {
            await read(month)
        }
    }

    /// The header's month title: back to this month.
    public func showToday() {
        today = KoreanCalendar.day(of: now())
        visibleMonth = currentMonth
    }

    public func show(month: String) {
        visibleMonth = month
    }

    private func read(_ month: String) async {
        let core = self.core
        guard let cells = try? await offMain({ try core.calendarMonth(month: month) }) else { return }
        pages[month] = cells
    }
}
