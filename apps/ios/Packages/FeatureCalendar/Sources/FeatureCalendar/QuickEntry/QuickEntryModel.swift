import Foundation
import GeunalCore
import Observation

/// The quick entry sheet (m1.md screen 5): five suggestion chips that save on a tap, or a title with a time range
/// dragged on the 0–24 strip. Saving goes through `EventActions`, which shows the toast.
@MainActor
@Observable
public final class QuickEntryModel {
    public enum Repeat: Hashable, CaseIterable, Sendable {
        case none
        case daily
        case weekly
        case monthly
        case yearly
    }

    /// Half hours in a day: the strip's steps (m1.md screen 5 "30분 단위").
    public static let slots = 48

    public let day: String
    public let today: String
    /// Birthdays and holidays of the day, for the line under the title.
    public let notes: [String]
    public private(set) var suggestions: [Suggestion] = []
    /// The calendars an event can go in: own ones, visible or not. Device calendars are read-only.
    public private(set) var calendars: [CalendarView] = []
    public var title = ""
    /// The chosen time in half hours from midnight, end exclusive; `nil` saves an all-day event.
    public var range: Range<Int>?
    public var calendarId: String?
    public var repeatChoice: Repeat = .none
    public private(set) var hasLoaded = false

    @ObservationIgnored private let core: any CoreProtocol

    public init(
        day: String, notes: [String] = [], range: Range<Int>? = nil, core: any CoreProtocol,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.day = day
        self.notes = notes
        self.range = range
        self.core = core
        today = KoreanCalendar.day(of: now())
    }

    public var heading: String { DayText.title(day, today: today) }

    public var selectedCalendar: CalendarView? {
        calendars.first { $0.id == calendarId } ?? calendars.first
    }

    public var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }

    public var canSave: Bool { !trimmedTitle.isEmpty && selectedCalendar != nil }

    /// The strip has a range but the title is empty: the button says what is missing (tone.md "오류와 한계").
    public var needsTitle: Bool { trimmedTitle.isEmpty && range != nil }

    public func load() async {
        let core = self.core
        let day = self.day
        async let calendars = try? offMain { try core.calendars() }
        async let suggestions = try? offMain { try core.suggestions(day: day) }
        self.calendars = (await calendars ?? []).filter { $0.kind == .own }
        self.suggestions = await suggestions ?? []
        if calendarId == nil {
            calendarId = self.calendars.first?.id
        }
        hasLoaded = true
    }

    /// The calendar color a chip's dot shows.
    public func color(of suggestion: Suggestion) -> Palette? {
        calendars.first { $0.id == suggestion.event.calendarId }?.color ?? selectedCalendar?.color
    }

    /// What "저장" saves, or `nil` while the title is empty.
    public func typedEvent() -> EventInput? {
        guard canSave, let calendar = selectedCalendar else { return nil }
        let start: DayTime
        let end: DayTime
        if let range {
            start = Self.time(slot: range.lowerBound, on: day)
            end = Self.time(slot: range.upperBound, on: day)
        } else {
            start = DayTime(day: day, minute: 0)
            end = DayTime(day: day, minute: 0)
        }
        return EventInput(
            calendarId: calendar.id, title: trimmedTitle, allDay: range == nil, start: start, end: end,
            recurrence: Self.recurrence(repeatChoice), alert: .inherit, note: nil)
    }

    /// What "자세히" hands to the editor: the typed title (or none yet), the range or all day, the calendar and the
    /// repeat rule.
    public func draft() -> EventInput {
        let range = self.range ?? 18..<20
        return EventInput(
            calendarId: selectedCalendar?.id ?? "", title: trimmedTitle, allDay: self.range == nil,
            start: Self.time(slot: self.range == nil ? 0 : range.lowerBound, on: day),
            end: self.range == nil ? DayTime(day: day, minute: 0) : Self.time(slot: range.upperBound, on: day),
            recurrence: Self.recurrence(repeatChoice), alert: .inherit, note: nil)
    }

    /// A half-hour step as a day and minute; step 48 is midnight at the start of the next day (rust-core.md §3).
    static func time(slot: Int, on day: String) -> DayTime {
        let minutes = slot * 30
        if minutes >= 1440 {
            return DayTime(day: KoreanCalendar.adding(days: 1, to: day) ?? day, minute: UInt16(minutes - 1440))
        }
        return DayTime(day: day, minute: UInt16(minutes))
    }

    /// The repeat chip's choices; a weekly or monthly rule without days falls on the first occurrence's.
    static func recurrence(_ choice: Repeat) -> Recurrence? {
        let frequency: Frequency
        switch choice {
        case .none: return nil
        case .daily: frequency = .daily
        case .weekly: frequency = .weekly
        case .monthly: frequency = .monthly
        case .yearly: frequency = .yearly
        }
        return Recurrence(frequency: frequency, interval: 1, weekdays: [], until: nil, count: nil)
    }

    /// The repeat chip's label for a choice: "반복 안 함", "매주 금요일".
    public func label(of choice: Repeat) -> String {
        guard let recurrence = Self.recurrence(choice) else {
            return String(localized: "반복 안 함", bundle: .module)
        }
        return RecurrenceText.describe(recurrence, startDay: day)
    }
}
