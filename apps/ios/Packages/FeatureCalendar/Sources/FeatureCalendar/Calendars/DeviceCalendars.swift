@preconcurrency import EventKit
import Foundation
import GeunalCore
import UIKit

/// The phone's calendars through EventKit, read only (docs/parts/ios.md §5): the app never writes to them. Each read
/// makes its own store off the main thread; the long-lived one is there for the change notice, which only a live
/// store posts.
@MainActor
public final class DeviceCalendars: DeviceCalendarSource {
    private var store = EKEventStore()
    private var observer: NSObjectProtocol?
    private var onChange: (@MainActor () -> Void)?

    public init() {}

    public func observeChanges(_ action: @escaping @MainActor () -> Void) {
        onChange = action
        watch()
    }

    public func access() -> DeviceCalendarAccess {
        switch EKEventStore.authorizationStatus(for: .event) {
        case .fullAccess: .granted
        case .notDetermined: .notAsked
        case .denied, .restricted, .writeOnly: .denied
        @unknown default: .denied
        }
    }

    public func requestAccess() async -> DeviceCalendarAccess {
        let asking = EKEventStore()
        _ = try? await asking.requestFullAccessToEvents()
        // A store made before the answer keeps the old authorization, and so would its change notice.
        store = EKEventStore()
        watch()
        return access()
    }

    public func calendars() async -> [DeviceCalendarSummary] {
        await Task.detached { Self.summaries(now: Date()) }.value
    }

    public func read(_ ids: [String]) async -> [DeviceCalendarInput] {
        await Task.detached { Self.inputs(ids, now: Date()) }.value
    }

    public func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private func watch() {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
        guard onChange != nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: .EKEventStoreChanged, object: store, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.onChange?() }
        }
    }

    /// A year back and two ahead: the reach of the month view's scrolling in practice, inside the four years EventKit
    /// answers in one query.
    private nonisolated static func window(now: Date) -> (start: Date, end: Date) {
        let calendar = KoreanCalendar.calendar
        let start = calendar.date(byAdding: .year, value: -1, to: now) ?? now
        let end = calendar.date(byAdding: .year, value: 2, to: now) ?? now
        return (start, end)
    }

    private nonisolated static func events(_ store: EKEventStore, of calendars: [EKCalendar], now: Date) -> [EKEvent] {
        guard !calendars.isEmpty else { return [] }
        let window = window(now: now)
        return store.events(
            matching: store.predicateForEvents(withStart: window.start, end: window.end, calendars: calendars))
    }

    /// "iCloud · 개인": phones often hold calendars of the same name in two accounts.
    private nonisolated static func name(of calendar: EKCalendar) -> String {
        guard let account = calendar.source?.title, !account.isEmpty else { return calendar.title }
        return "\(account) · \(calendar.title)"
    }

    /// "일정 N개" counts events, not their repeats.
    private nonisolated static func summaries(now: Date) -> [DeviceCalendarSummary] {
        let store = EKEventStore()
        let calendars = store.calendars(for: .event)
        var events: [String: Set<String>] = [:]
        for event in Self.events(store, of: calendars, now: now) {
            guard let calendar = event.calendar else { continue }
            events[calendar.calendarIdentifier, default: []].insert(event.calendarItemIdentifier)
        }
        return calendars.map {
            DeviceCalendarSummary(
                id: $0.calendarIdentifier, name: name(of: $0), eventCount: events[$0.calendarIdentifier]?.count ?? 0)
        }
    }

    private nonisolated static func inputs(_ ids: [String], now: Date) -> [DeviceCalendarInput] {
        let store = EKEventStore()
        let calendars = ids.compactMap { store.calendar(withIdentifier: $0) }
        let local = Calendar.autoupdatingCurrent
        var events: [String: [DeviceEventInput]] = [:]
        for event in Self.events(store, of: calendars, now: now) {
            guard let calendar = event.calendar, let start = event.startDate, let end = event.endDate else { continue }
            let id = DeviceEventDates.occurrenceId(
                event: event.eventIdentifier ?? event.calendarItemIdentifier, start: start)
            events[calendar.calendarIdentifier, default: []].append(
                DeviceEventDates.input(
                    id: id, title: event.title ?? "", allDay: event.isAllDay, start: start, end: end, local: local))
        }
        return calendars.map {
            DeviceCalendarInput(
                sourceId: $0.calendarIdentifier, name: name(of: $0), events: events[$0.calendarIdentifier] ?? [])
        }
    }
}
