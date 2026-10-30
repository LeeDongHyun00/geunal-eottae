import Foundation
import GeunalCore
import Observation

public enum DeviceCalendarAccess: Equatable, Sendable {
    case notAsked
    case granted
    case denied
}

/// One of the phone's calendars as 캘린더 관리 lists it: what the checkbox shows before anything is mirrored.
public struct DeviceCalendarSummary: Equatable, Identifiable, Sendable {
    /// EventKit's `calendarIdentifier`, the mirror's source id.
    public let id: String
    public let name: String
    public let eventCount: Int

    public init(id: String, name: String, eventCount: Int) {
        self.id = id
        self.name = name
        self.eventCount = eventCount
    }
}

/// The phone's calendars behind a seam, so the mirror can be tested without EventKit.
@MainActor
public protocol DeviceCalendarSource: AnyObject {
    func access() -> DeviceCalendarAccess
    func requestAccess() async -> DeviceCalendarAccess
    func calendars() async -> [DeviceCalendarSummary]
    /// The calendars with these ids and their events in the mirrored window, each repeat as its own occurrence.
    func read(_ ids: [String]) async -> [DeviceCalendarInput]
    func openSettings()
    /// Calls `action` whenever the phone's calendar database changes, from any app.
    func observeChanges(_ action: @escaping @MainActor () -> Void)
}

/// The device calendar mirror (m1.md screen 15's second group, decision 5): read only, titles stay on this phone.
/// The core keeps which calendars are mirrored; the app reads them again whenever the phone's calendars may have
/// changed (the store's change notice, coming to the front, a background refresh).
@MainActor
@Observable
public final class DeviceCalendarMirror {
    public private(set) var access: DeviceCalendarAccess = .notAsked
    public private(set) var calendars: [DeviceCalendarSummary] = []
    /// The ticked calendars. While the switch is off they keep the last ticks, which the dimmed list still shows.
    public private(set) var selected: Set<String> = []
    public private(set) var isOn = false
    public private(set) var loaded = false
    public private(set) var failed = false
    public private(set) var isWorking = false

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let source: any DeviceCalendarSource
    @ObservationIgnored private let coalescer = Coalescer()

    public init(core: any CoreProtocol, source: any DeviceCalendarSource) {
        self.core = core
        self.source = source
    }

    /// The core names a mirrored calendar `device:` and its source id (core/geunal-ffi `mirror_device_calendars`).
    nonisolated static let idPrefix = "device:"

    /// The source ids the core mirrors now.
    public func mirrored() async -> Set<String> {
        let core = self.core
        let all = (try? await offMain { try core.calendars() }) ?? []
        return Set(
            all.filter { $0.kind == .device && $0.id.hasPrefix(Self.idPrefix) }
                .map { String($0.id.dropFirst(Self.idPrefix.count)) })
    }

    /// 캘린더 관리 opens: the phone's calendars and which of them the core mirrors.
    public func load() async {
        access = source.access()
        let mirrored = await mirrored()
        calendars = access == .granted ? await source.calendars() : []
        isOn = !mirrored.isEmpty
        if isOn || selected.isEmpty {
            selected = mirrored
        }
        loaded = true
    }

    /// The switch. On asks for access the first time and ticks every calendar, or the ticks the switch last turned
    /// off; off empties the mirror and keeps the ticks for the dimmed list.
    public func setOn(_ on: Bool) async {
        guard on != isOn, !isWorking else { return }
        if !on {
            if await mirror([]) {
                isOn = false
            }
            return
        }
        if access == .notAsked {
            access = await source.requestAccess()
            if access == .granted {
                calendars = await source.calendars()
            }
        }
        guard access == .granted else { return }
        let ids = selected.isEmpty ? calendars.map(\.id) : calendars.map(\.id).filter(selected.contains)
        guard !ids.isEmpty else { return }
        if await mirror(ids) {
            selected = Set(ids)
            isOn = true
        }
    }

    /// A checkbox. Unticking the last calendar turns the mirror off, as the core does with an empty list.
    public func toggle(_ id: String) async {
        guard isOn, !isWorking else { return }
        var next = selected
        if next.contains(id) {
            next.remove(id)
        } else {
            next.insert(id)
        }
        let ids = calendars.map(\.id).filter(next.contains)
        if await mirror(ids) {
            selected = next
            isOn = !ids.isEmpty
        }
    }

    /// Onboarding's "가져오기" after the system said yes: every calendar on the phone. The count is what the card says,
    /// "가져왔어요 · 캘린더 3개"; `nil` when the core refused.
    public func importAll() async -> Int? {
        access = source.access()
        guard access == .granted else { return nil }
        calendars = await source.calendars()
        let ids = calendars.map(\.id)
        guard await mirror(ids) else { return nil }
        selected = Set(ids)
        isOn = !ids.isEmpty
        return ids.count
    }

    /// The phone's calendars may have changed: read the mirrored ones again. A calendar deleted on the phone drops
    /// out of the mirror, and nothing happens while the mirror is off or the access was taken back in Settings.
    public func refresh() async {
        await coalescer.run { [self] in
            guard source.access() == .granted else { return }
            let mirrored = await mirrored()
            guard !mirrored.isEmpty else { return }
            let present = await source.calendars().map(\.id)
            _ = await mirror(present.filter(mirrored.contains))
        }
    }

    public func openSettings() {
        source.openSettings()
    }

    private func mirror(_ ids: [String]) async -> Bool {
        isWorking = true
        defer { isWorking = false }
        let inputs = ids.isEmpty ? [] : await source.read(ids)
        let core = self.core
        do {
            _ = try await offMain { try core.mirrorDeviceCalendars(calendars: inputs) }
            failed = false
            return true
        } catch {
            failed = true
            return false
        }
    }
}

/// How an event of the phone's calendars becomes the core's input, in Korean days and minutes (rust-core.md §3).
public enum DeviceEventDates {
    /// The occurrence's id: a repeating event shares one `eventIdentifier` across its repeats, and the core keys each
    /// occurrence by its own id.
    public static func occurrenceId(event: String, start: Date) -> String {
        "\(event)@\(Int64(start.timeIntervalSince1970))"
    }

    /// A timed event keeps its instants, read in Korean time. An all-day event floats: its dates are midnights in
    /// the phone's own time zone (`local`), and EventKit ends it at its last day's 23:59:59, or at the next midnight.
    public static func input(
        id: String, title: String, allDay: Bool, start: Date, end: Date, local: Calendar
    ) -> DeviceEventInput {
        guard allDay else {
            return DeviceEventInput(
                deviceEventId: id, title: title, allDay: false, start: korean(start), end: korean(end))
        }
        let first = day(start, in: local)
        let last = max(first, day(end.addingTimeInterval(-1), in: local))
        return DeviceEventInput(
            deviceEventId: id, title: title, allDay: true, start: DayTime(day: first, minute: 0),
            end: DayTime(day: last, minute: 0))
    }

    private static func day(_ date: Date, in calendar: Calendar) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04ld-%02ld-%02ld", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    private static func korean(_ date: Date) -> DayTime {
        let parts = KoreanCalendar.calendar.dateComponents([.hour, .minute], from: date)
        return DayTime(day: KoreanCalendar.day(of: date), minute: UInt16((parts.hour ?? 0) * 60 + (parts.minute ?? 0)))
    }
}
