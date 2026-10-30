import Foundation
import GeunalCore
import Observation

/// The shift stamp screen (m1.md screen 10): pick a template, tap days. The taps stay a draft until "완료" hands the
/// changed days to the core in one call (`setShiftDays`), so "취소" changes nothing (#29 decision 6).
@MainActor
@Observable
public final class ShiftStampModel {
    public private(set) var month: String
    public private(set) var templates: [ShiftTemplate] = []
    public private(set) var calendars: [CalendarView] = []
    public var selectedTemplateId: String?
    public private(set) var hasLoaded = false
    public private(set) var isSaving = false
    public private(set) var failed = false

    /// The days as the core had them, and as the screen has them now.
    @ObservationIgnored private var saved: [String: [String]] = [:]
    private var draft: [String: [String]] = [:]
    @ObservationIgnored private var loadedMonths: Set<String> = []
    @ObservationIgnored private let core: any CoreProtocol

    public init(month: String, core: any CoreProtocol) {
        self.month = month
        self.core = core
    }

    public var selectedTemplate: ShiftTemplate? {
        templates.first { $0.id == selectedTemplateId }
    }

    /// The days whose templates differ from the core's; "완료" is on only when there are some.
    public var changedDays: [ShiftDay] {
        let days = Set(saved.keys).union(draft.keys)
        return days.sorted().compactMap { day in
            let now = draft[day] ?? []
            return now == (saved[day] ?? []) ? nil : ShiftDay(day: day, templateIds: now)
        }
    }

    public var hasChanges: Bool { !changedDays.isEmpty }

    /// The month's grid, whole weeks from Sunday like the month view.
    public var weeks: [[String]] {
        guard let first = KoreanCalendar.weekStart(of: month + "-01") else { return [] }
        var weeks: [[String]] = []
        var day = first
        repeat {
            let week = (0..<7).compactMap { KoreanCalendar.adding(days: $0, to: day) }
            weeks.append(week)
            day = KoreanCalendar.adding(days: 7, to: day) ?? day
        } while KoreanCalendar.month(of: day) == month
        return weeks
    }

    public func templates(on day: String) -> [ShiftTemplate] {
        (draft[day] ?? []).compactMap { id in templates.first { $0.id == id } }
    }

    /// "11월 9번": the days of the month with a shift.
    public var countThisMonth: Int {
        draft.filter { KoreanCalendar.month(of: $0.key) == month && !$0.value.isEmpty }.count
    }

    /// The calendar a template stamps into, for "찍은 근무는 '알바' 캘린더의 일반 일정이 돼요".
    public func calendarName(of template: ShiftTemplate) -> String? {
        calendars.first { $0.id == template.calendarId }?.name
    }

    public func load() async {
        let core = self.core
        calendars = (try? await offMain { try core.calendars() }) ?? []
        templates = (try? await offMain { try core.shiftTemplates() }) ?? []
        if selectedTemplateId == nil || selectedTemplate == nil {
            selectedTemplateId = templates.first?.id
        }
        await loadMonth(month)
        hasLoaded = true
    }

    public func show(month: String) async {
        self.month = month
        await loadMonth(month)
    }

    public func step(months: Int) async {
        guard let next = KoreanCalendar.adding(months: months, to: month) else { return }
        await show(month: next)
    }

    /// A tap with the chosen template: the same template again takes it off, another one is replaced by it.
    public func tap(day: String) {
        guard let template = selectedTemplateId else { return }
        let current = draft[day] ?? []
        draft[day] = current == [template] ? [] : [template]
    }

    /// After the template sheet saved one: the list again, with the new or edited template chosen.
    public func templateSaved(_ id: String) async {
        let core = self.core
        // A first template may have made the '알바' calendar.
        calendars = (try? await offMain { try core.calendars() }) ?? calendars
        templates = (try? await offMain { try core.shiftTemplates() }) ?? templates
        selectedTemplateId = id
    }

    /// After the template sheet deleted one: its stamped shifts stay as ordinary events (rust-core.md §3).
    public func templateDeleted(_ id: String) async {
        let core = self.core
        templates = (try? await offMain { try core.shiftTemplates() }) ?? templates
        if selectedTemplateId == id {
            selectedTemplateId = templates.first?.id
        }
        for (day, ids) in draft where ids.contains(id) {
            draft[day] = ids.filter { $0 != id }
            saved[day] = (saved[day] ?? []).filter { $0 != id }
        }
    }

    /// "완료": the changed days in one call.
    public func commit() async -> Bool {
        let days = changedDays
        guard !days.isEmpty else { return true }
        isSaving = true
        defer { isSaving = false }
        let core = self.core
        do {
            try await offMain { try core.setShiftDays(days: days) }
            for day in days {
                saved[day.day] = day.templateIds
            }
            failed = false
            return true
        } catch {
            failed = true
            return false
        }
    }

    private func loadMonth(_ month: String) async {
        guard !loadedMonths.contains(month) else { return }
        let core = self.core
        guard let days = try? await offMain({ try core.shiftDays(month: month) }) else { return }
        loadedMonths.insert(month)
        for day in days where draft[day.day] == nil {
            saved[day.day] = day.templateIds
            draft[day.day] = day.templateIds
        }
    }
}
