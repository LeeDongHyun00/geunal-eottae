import Foundation
import GeunalCore
import Observation

/// 캘린더 관리 (m1.md screen 15): my calendars with their color, what they hold and the show switch. Hiding one takes
/// it off the month, week and list here only; groups still see the time as busy. Device calendars join with the
/// mirror (screen 15's second group).
@MainActor
@Observable
public final class CalendarsModel {
    public private(set) var calendars: [CalendarView] = []
    public private(set) var loaded = false
    public private(set) var failed = false

    @ObservationIgnored private let core: any CoreProtocol

    public init(core: any CoreProtocol) {
        self.core = core
    }

    public var own: [CalendarView] { calendars.filter { $0.kind == .own } }

    public func load() async {
        let core = self.core
        calendars = (try? await offMain { try core.calendars() }) ?? []
        loaded = true
    }

    public func setVisible(_ calendarId: String, _ visible: Bool) async {
        let core = self.core
        do {
            try await offMain { try core.setCalendarVisible(calendarId: calendarId, visible: visible) }
            failed = false
        } catch {
            failed = true
        }
        await load()
    }

    /// "바다 · 일정 42개", "민트 · 교대 템플릿 3개", "올리브 · 소프트 캘린더, 그룹에는 비어 있음으로".
    public static func detail(_ calendar: CalendarView) -> String {
        let color = calendar.color?.groupColor.name ?? ""
        let what: String
        if calendar.soft {
            what = String(localized: "소프트 캘린더, 그룹에는 비어 있음으로", bundle: .module)
        } else if calendar.shiftTemplateCount > 0 {
            what = String(localized: "교대 템플릿 \(Int(calendar.shiftTemplateCount))개", bundle: .module)
        } else {
            what = String(localized: "일정 \(Int(calendar.eventCount))개", bundle: .module)
        }
        return color.isEmpty ? what : String(localized: "\(color) · \(what)", bundle: .module)
    }
}

/// 캘린더 편집 (m1.md screen 16): the name, one of the twelve colors, soft or not, and the default alert. Deleting
/// says how many events go with it; the last calendar of one's own stays (m1.md "캘린더는 하나 이상 있어야 해요").
@MainActor
@Observable
public final class CalendarEditModel {
    public let calendarId: String?
    public let eventCount: UInt32
    public let isLast: Bool
    public var name = ""
    public var color: Palette = .sky
    public var soft = false
    public var defaultAlert: AlertRule = .inherit
    public private(set) var isWorking = false
    public private(set) var failed = false

    @ObservationIgnored private let core: any CoreProtocol

    /// `isLast` says the calendar is the only one of one's own, which cannot be deleted.
    public init(calendar: CalendarView? = nil, isLast: Bool = false, core: any CoreProtocol) {
        calendarId = calendar?.id
        eventCount = calendar?.eventCount ?? 0
        self.isLast = isLast
        self.core = core
        if let calendar {
            name = calendar.name
            color = calendar.color ?? .sky
            soft = calendar.soft
            defaultAlert = calendar.defaultAlert
        }
    }

    public var isNew: Bool { calendarId == nil }

    public var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    public var canSave: Bool { !trimmedName.isEmpty && !isWorking }

    public var canDelete: Bool { !isNew && !isLast && !isWorking }

    public static let colors: [Palette] = [
        .tomato, .tangerine, .mustard, .olive, .forest, .mint, .sky, .sea, .grape, .plum, .blossom, .cocoa,
    ]

    /// The calendar's id, or `nil` when the core refused.
    public func save() async -> String? {
        guard canSave else { return nil }
        isWorking = true
        defer { isWorking = false }
        let input = CalendarInput(
            id: calendarId, name: trimmedName, color: color, soft: soft, defaultAlert: defaultAlert)
        let core = self.core
        do {
            let id = try await offMain { try core.saveCalendar(input: input) }
            failed = false
            return id
        } catch {
            failed = true
            return nil
        }
    }

    public func delete() async -> Bool {
        guard canDelete, let calendarId else { return false }
        isWorking = true
        defer { isWorking = false }
        let core = self.core
        do {
            try await offMain { try core.deleteCalendar(calendarId: calendarId) }
            failed = false
            return true
        } catch {
            failed = true
            return false
        }
    }
}
