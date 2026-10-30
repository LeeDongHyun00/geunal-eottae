import Foundation
import GeunalCore
import Observation

/// The shift template sheet (m1.md screen 18): a name, start and end (an end at or before the start runs to the next
/// day), the calendar the shifts go in (the core's '알바' when none is picked) and the alert.
@MainActor
@Observable
public final class TemplateEditModel {
    public let templateId: String?
    public var name = ""
    public var startMinute = 540
    public var endMinute = 1080
    public var calendarId: String?
    public var alert: AlertRule = .inherit
    public private(set) var calendars: [CalendarView] = []
    /// The template's own color, for the preview card of a template that exists.
    public private(set) var color: Palette?
    public private(set) var tint: UInt8 = 3
    public private(set) var isWorking = false
    public private(set) var failed = false

    @ObservationIgnored private let core: any CoreProtocol

    public init(template: ShiftTemplate? = nil, core: any CoreProtocol) {
        templateId = template?.id
        self.core = core
        if let template {
            name = template.name
            startMinute = Int(template.startMinute)
            endMinute = Int(template.endMinute)
            calendarId = template.calendarId
            alert = template.alert
            color = template.color
            tint = template.tint
        }
    }

    public var isNew: Bool { templateId == nil }

    /// 22:00–02:00 is one shift into the next day (m1.md screen 18 "상태").
    public var crossesMidnight: Bool { endMinute <= startMinute }

    public var lengthMinutes: Int {
        crossesMidnight ? endMinute + 1440 - startMinute : endMinute - startMinute
    }

    public var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    public var canSave: Bool { !trimmedName.isEmpty && startMinute != endMinute && !isWorking }

    public var ownCalendars: [CalendarView] { calendars.filter { $0.kind == .own } }

    /// The calendar the preview shows: the picked one, the template's, or the one the core will make.
    public var selectedCalendar: CalendarView? { ownCalendars.first { $0.id == calendarId } }

    public var previewColor: Palette? { selectedCalendar?.color ?? color }

    public func load() async {
        let core = self.core
        calendars = (try? await offMain { try core.calendars() }) ?? []
    }

    /// The template's id, or `nil` when the core refused.
    public func save() async -> String? {
        guard canSave else { return nil }
        isWorking = true
        defer { isWorking = false }
        let input = ShiftTemplateInput(
            id: templateId, name: trimmedName, startMinute: UInt16(startMinute % 1440),
            endMinute: UInt16(endMinute % 1440), calendarId: calendarId, alert: alert)
        let core = self.core
        do {
            let id = try await offMain { try core.saveShiftTemplate(input: input) }
            failed = false
            return id
        } catch {
            failed = true
            return nil
        }
    }

    public func delete() async -> Bool {
        guard let templateId, !isWorking else { return false }
        isWorking = true
        defer { isWorking = false }
        let core = self.core
        do {
            try await offMain { try core.deleteShiftTemplate(templateId: templateId) }
            failed = false
            return true
        } catch {
            failed = true
            return false
        }
    }
}
