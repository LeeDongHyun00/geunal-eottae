import Foundation
import GeunalCore
import Observation

/// The next-up bar above the tab bar (docs/design/README.md §5): the nearest things from now, stepped through with
/// ‹ ›. M1 has own events and birthdays; group appointments join at M2.
@MainActor
@Observable
public final class NextUpModel {
    public struct Item: Equatable, Sendable {
        public let title: String
        public let detail: String
        public let isBirthday: Bool
        public let color: Palette?
        public let source: UpNext
    }

    public private(set) var items: [Item] = []
    public private(set) var index = 0

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private let count: UInt16

    public init(
        core: any CoreProtocol, count: UInt16 = 12, now: @escaping @Sendable () -> Date = Date.init,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.core = core
        self.count = count
        self.now = now
        self.sleep = sleep
    }

    public var current: Item? { items.indices.contains(index) ? items[index] : nil }
    public var canGoBack: Bool { index > 0 }
    public var canGoForward: Bool { index + 1 < items.count }

    /// Reads the core again, keeping the bar on the same item when it is still there.
    public func refresh() async {
        let core = self.core
        let count = self.count
        let upcoming = await Task.detached { (try? core.nextUp(count: count)) ?? [] }.value
        let previous = current?.source
        let now = self.now()
        items = upcoming.map { Self.item($0, now: now) }
        index = previous.flatMap { source in items.firstIndex { $0.source == source } } ?? 0
    }

    /// Reads the core again at every minute while the bar is on screen, until the calling task is cancelled. Nothing
    /// else refreshes it while the app stays in front without edits, and "5분 뒤" would still be on the bar after the
    /// event began, ended, or the day turned.
    public func keepCurrent() async {
        while true {
            let intoMinute = now().timeIntervalSince1970.truncatingRemainder(dividingBy: 60)
            do {
                try await self.sleep(.seconds(60 - intoMinute))
            } catch {
                return
            }
            await refresh()
        }
    }

    public func forward() {
        if canGoForward { index += 1 }
    }

    public func back() {
        if canGoBack { index -= 1 }
    }

    static func item(_ upNext: UpNext, now: Date) -> Item {
        let today = KoreanCalendar.day(of: now)
        switch upNext {
        case .event(let event, let startsAt, _):
            let day = DayText.relativeDay(event.start.day, today: today)
            let detail: String
            if event.allDay {
                detail = String(localized: "\(day) · 종일", bundle: .module)
            } else {
                let range = DayText.range(start: event.start.minute, end: Self.endMinute(event))
                if let until = DayText.until(startsAt, now: now) {
                    detail = String(localized: "\(day) \(range) · \(until)", bundle: .module)
                } else {
                    detail = String(localized: "\(day) \(range) · 지금", bundle: .module)
                }
            }
            return Item(title: event.title, detail: detail, isBirthday: false, color: event.color, source: upNext)
        case .birthday(let birthday):
            return Item(
                title: String(localized: "\(birthday.name) 생일", bundle: .module),
                detail: DayText.relativeDay(birthday.day, today: today),
                isBirthday: true, color: nil, source: upNext)
        }
    }

    /// An event ending at midnight ends at minute 0 of the next day (rust-core.md §3); show it as 24:00.
    private static func endMinute(_ event: EventItem) -> UInt16 {
        event.end.day != event.start.day && event.end.minute == 0 ? 1440 : event.end.minute
    }
}
