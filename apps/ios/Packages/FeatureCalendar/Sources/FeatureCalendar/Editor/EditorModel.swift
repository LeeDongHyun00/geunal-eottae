import Foundation
import GeunalCore
import Observation

/// The event editor (m1.md screen 9): a new event, or one opened from the views. Times are Korean days and minutes
/// as the core keeps them; moving the start moves the end with it so the length stays.
@MainActor
@Observable
public final class EditorModel {
    public enum Origin: Equatable, Sendable {
        case new
        /// `occurrence` names one occurrence of a repeating event, which may then change alone or with what follows.
        case existing(eventId: String, occurrence: String?)
    }

    public enum Problem: Equatable, Sendable {
        case notFound
        case failed
    }

    public let origin: Origin
    public var title = ""
    public var calendarId: String?
    public var allDay = false
    public private(set) var startDay: String
    public private(set) var startMinute = 540
    public private(set) var endDay: String
    public private(set) var endMinute = 600
    public var recurrence: Recurrence?
    public var alert: AlertRule = .inherit
    /// The alert that applies after inheritance, from the core, for the "1시간 전" label of an event that inherits.
    public private(set) var effectiveAlert: Int32?
    public private(set) var calendars: [CalendarView] = []
    public private(set) var readOnly = false
    public private(set) var source: EventSource = .own
    /// The event repeated when it was opened: saving or deleting an occurrence asks "이 일정만 · 이후 모두".
    public private(set) var wasRepeating = false
    public private(set) var hasLoaded = false
    public private(set) var problem: Problem?
    public private(set) var isWorking = false

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private var note: String?

    /// A new event on `day`, filled from `input` when the quick entry's "자세히" hands one over.
    public init(newOn day: String, input: EventInput? = nil, core: any CoreProtocol) {
        origin = .new
        self.core = core
        startDay = day
        endDay = day
        if let input {
            apply(input)
        }
    }

    public init(eventId: String, occurrence: String?, core: any CoreProtocol) {
        origin = .existing(eventId: eventId, occurrence: occurrence)
        self.core = core
        startDay = ""
        endDay = ""
    }

    public var isNew: Bool { origin == .new }

    /// Own calendars to pick from; a device calendar's event shows its own and cannot move.
    public var ownCalendars: [CalendarView] { calendars.filter { $0.kind == .own } }

    public var selectedCalendar: CalendarView? {
        calendars.first { $0.id == calendarId } ?? ownCalendars.first
    }

    public var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The end comes after the start: a timed event by at least a minute, an all-day one on the same day or later.
    public var hasValidRange: Bool {
        guard let start = absolute(startDay, startMinute), let end = absolute(endDay, endMinute) else { return false }
        return allDay ? end / 1440 >= start / 1440 : end > start
    }

    public var canSave: Bool {
        !readOnly && !isWorking && !trimmedTitle.isEmpty && hasValidRange && selectedCalendar?.kind == .own
    }

    /// Saving or deleting asks which occurrences it is for.
    public var asksScope: Bool {
        guard case .existing(_, let occurrence) = origin else { return false }
        return wasRepeating && occurrence != nil
    }

    public func load() async {
        let core = self.core
        let calendars = (try? await offMain { try core.calendars() }) ?? []
        self.calendars = calendars
        if case .existing(let eventId, let occurrence) = origin {
            guard let detail = try? await offMain({ try core.event(eventId: eventId, occurrence: occurrence) }) else {
                problem = .notFound
                hasLoaded = true
                return
            }
            title = detail.title
            calendarId = detail.calendarId
            setTimes(allDay: detail.allDay, start: detail.start, end: detail.end)
            recurrence = detail.recurrence
            alert = detail.alert
            effectiveAlert = detail.effectiveAlert
            note = detail.note
            source = detail.source
            readOnly = detail.readOnly
            wasRepeating = detail.recurrence != nil
        }
        if calendarId == nil {
            calendarId = ownCalendars.first?.id
        }
        hasLoaded = true
    }

    /// Moves the start; the end follows so the event keeps its length (m1.md screen 9 "동작").
    public func setStart(day: String, minute: Int) {
        let length = duration ?? 60
        startDay = day
        startMinute = minute
        if let start = absolute(day, minute) {
            (endDay, endMinute) = dayAndMinute(start + length, near: day)
        }
    }

    public func setEnd(day: String, minute: Int) {
        endDay = day
        endMinute = minute
    }

    /// Turning "종일" off puts the event at 09:00–10:00 of its first day.
    public func setAllDay(_ on: Bool) {
        guard on != allDay else { return }
        allDay = on
        if !on {
            startMinute = 540
            endDay = startDay
            endMinute = 600
        }
    }

    /// What "저장" sends.
    public func input() -> EventInput? {
        guard let calendar = selectedCalendar, hasValidRange else { return nil }
        let start = DayTime(day: startDay, minute: allDay ? 0 : UInt16(startMinute))
        let end: DayTime
        if allDay {
            end = DayTime(day: endDay, minute: 0)
        } else {
            // 24:00 is midnight at the start of the next day (rust-core.md §3).
            let (day, minute) =
                endMinute >= 1440 ? (KoreanCalendar.adding(days: 1, to: endDay) ?? endDay, 0) : (endDay, endMinute)
            end = DayTime(day: day, minute: UInt16(minute))
        }
        return EventInput(
            calendarId: calendar.id, title: trimmedTitle, allDay: allDay, start: start, end: end,
            recurrence: recurrence, alert: alert, note: note)
    }

    /// Saves a new event or the edit; `scope` is the answer to "이 일정만 · 이후 모두" when `asksScope`.
    public func save(scope: EditScope = .thisAndFollowing) async -> Bool {
        guard canSave, let input = input() else { return false }
        isWorking = true
        defer { isWorking = false }
        let core = self.core
        do {
            switch origin {
            case .new:
                _ = try await offMain { try core.addEvent(input: input, fromSuggestion: false) }
            case .existing(let eventId, let occurrence):
                _ = try await offMain {
                    try core.updateEvent(eventId: eventId, occurrence: occurrence, scope: scope, input: input)
                }
            }
            problem = nil
            return true
        } catch {
            problem = .failed
            return false
        }
    }

    public func delete(scope: EditScope = .thisAndFollowing) async -> Bool {
        guard case .existing(let eventId, let occurrence) = origin, !readOnly, !isWorking else { return false }
        isWorking = true
        defer { isWorking = false }
        let core = self.core
        do {
            try await offMain { try core.deleteEvent(eventId: eventId, occurrence: occurrence, scope: scope) }
            problem = nil
            return true
        } catch {
            problem = .failed
            return false
        }
    }

    // MARK: Times

    private var duration: Int? {
        guard let start = absolute(startDay, startMinute), let end = absolute(endDay, endMinute) else { return nil }
        return end - start
    }

    /// Minutes from an arbitrary fixed day, so lengths across midnight and month ends are plain subtraction.
    private func absolute(_ day: String, _ minute: Int) -> Int? {
        KoreanCalendar.days(from: Self.epoch, to: day).map { $0 * 1440 + minute }
    }

    private func dayAndMinute(_ absolute: Int, near day: String) -> (String, Int) {
        let days = Int((Double(absolute) / 1440).rounded(.down))
        let minute = absolute - days * 1440
        return (KoreanCalendar.adding(days: days, to: Self.epoch) ?? day, minute)
    }

    private static let epoch = "2000-01-01"

    private func apply(_ input: EventInput) {
        title = input.title
        calendarId = input.calendarId
        setTimes(allDay: input.allDay, start: input.start, end: input.end)
        recurrence = input.recurrence
        alert = input.alert
        note = input.note
    }

    private func setTimes(allDay: Bool, start: DayTime, end: DayTime) {
        self.allDay = allDay
        startDay = start.day
        startMinute = allDay ? 540 : Int(start.minute)
        if allDay {
            endDay = end.day
            endMinute = 600
        } else if end.minute == 0, KoreanCalendar.adding(days: 1, to: start.day) == end.day {
            // Midnight at the end of the day reads as 24:00 of the start day.
            endDay = start.day
            endMinute = 1440
        } else {
            endDay = end.day
            endMinute = Int(end.minute)
        }
    }
}
