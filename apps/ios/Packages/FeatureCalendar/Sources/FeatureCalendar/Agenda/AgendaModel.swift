import Foundation
import GeunalCore
import Observation

/// The list view (m1.md screen 8): the days with something on them, today first, four weeks a page. Scrolling up
/// brings the four weeks before, greyed out.
@MainActor
@Observable
public final class AgendaModel {
    /// Days per page; an empty first page is the empty state ("앞으로 4주 동안 일정이 없어요").
    public static let pageDays: UInt16 = 28
    /// How far back scrolling up and forward scrolling down may go.
    static let earliestDays = 366
    static let latestDays = 3 * 366

    public private(set) var days: [AgendaDay] = []
    public private(set) var today: String
    /// Nothing from today on in the first page.
    public private(set) var isEmpty = false
    public private(set) var hasLoaded = false
    public private(set) var canLoadEarlier = true
    public private(set) var canLoadMore = true
    /// The first day not loaded yet; the list's bottom edge reappears under a new identity after each page.
    public var loadedThrough: String { nextDay }

    @ObservationIgnored private var firstDay: String
    @ObservationIgnored private var nextDay: String
    @ObservationIgnored private var isLoading = false
    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let now: @Sendable () -> Date

    public init(core: any CoreProtocol, now: @escaping @Sendable () -> Date = Date.init) {
        self.core = core
        self.now = now
        let today = KoreanCalendar.day(of: now())
        self.today = today
        firstDay = today
        nextDay = today
    }

    public func isPast(_ day: AgendaDay) -> Bool {
        (KoreanCalendar.days(from: today, to: day.day) ?? 0) < 0
    }

    /// The first page from today, and the page before it so scrolling up has somewhere to go.
    public func reload() async {
        today = KoreanCalendar.day(of: now())
        let start = KoreanCalendar.adding(days: -Int(Self.pageDays), to: today) ?? today
        let end = KoreanCalendar.adding(days: Int(Self.pageDays), to: today) ?? today
        guard let found = await read(from: start, days: 2 * Self.pageDays) else { return }
        days = found
        firstDay = start
        nextDay = end
        // A holiday alone is not an event: the empty state still says there is nothing planned.
        isEmpty = !found.contains { !isPast($0) && !($0.events.isEmpty && $0.birthdays.isEmpty) }
        canLoadEarlier = true
        canLoadMore = true
        hasLoaded = true
    }

    /// The next four weeks, when the list reaches its end.
    public func loadMore() async {
        guard hasLoaded, canLoadMore, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        let start = nextDay
        guard let ahead = KoreanCalendar.days(from: today, to: start), ahead < Self.latestDays else {
            canLoadMore = false
            return
        }
        guard let found = await read(from: start, days: Self.pageDays) else { return }
        days.append(contentsOf: found)
        nextDay = KoreanCalendar.adding(days: Int(Self.pageDays), to: start) ?? start
    }

    /// The four weeks before, when the list reaches its top.
    public func loadEarlier() async {
        guard hasLoaded, canLoadEarlier, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        guard let start = KoreanCalendar.adding(days: -Int(Self.pageDays), to: firstDay),
            let back = KoreanCalendar.days(from: start, to: today), back <= Self.earliestDays
        else {
            canLoadEarlier = false
            return
        }
        guard let found = await read(from: start, days: Self.pageDays) else { return }
        days.insert(contentsOf: found, at: 0)
        firstDay = start
    }

    private func read(from day: String, days: UInt16) async -> [AgendaDay]? {
        let core = self.core
        return try? await offMain { try core.calendarAgenda(fromDay: day, days: days) }
    }
}
