import DesignSystem
import GeunalCore
import SwiftUI
import UIKit

/// The calendar tab (m1.md screens 4–8): the header with the 월·주·목록 segment, the chosen view, and the quick entry
/// sheet a day opens. The view last shown comes back on the next launch (m1.md §2.2).
public struct CalendarScreen: View {
    public enum Kind: String, CaseIterable, Sendable {
        case month
        case week
        case list
    }

    private let core: any CoreProtocol
    private let changes: Int
    @Binding private var openEvent: EventItem?
    @State private var month: MonthModel
    @State private var week: WeekModel
    @State private var agenda: AgendaModel
    @State private var entry: EntryRequest?
    @State private var editor: EditorRequest?
    /// "자세히" closes the quick entry first; the editor follows once the sheet is gone.
    @State private var pendingEditor: EditorRequest?
    @State private var stamping: StampRequest?
    /// The Korean day the views were read for. An app left open overnight, or opened again the next day, reads them
    /// again for the new today.
    @State private var shownDay: String
    @AppStorage("app.geunal.calendar.view") private var kind: Kind = .month
    @Environment(\.scenePhase) private var scenePhase
    private let actions: EventActions
    private let now: @Sendable () -> Date

    /// `changes` counts the core's calendar changes; each new value reads the views again. An event set in
    /// `openEvent` (the next-up bar's) opens in the editor.
    public init(
        core: any CoreProtocol, toasts: ToastCenter, changes: Int, openEvent: Binding<EventItem?> = .constant(nil),
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.core = core
        self.changes = changes
        _openEvent = openEvent
        self.now = now
        actions = EventActions(core: core, toasts: toasts)
        _shownDay = State(initialValue: KoreanCalendar.day(of: now()))
        _month = State(initialValue: MonthModel(core: core, now: now))
        _week = State(initialValue: WeekModel(core: core, now: now))
        _agenda = State(initialValue: AgendaModel(core: core, now: now))
    }

    public var body: some View {
        VStack(spacing: 0) {
            CalendarHeader(
                kind: $kind, caption: heading.caption, title: heading.title,
                titleHint: kind == .list ? nil : Text("오늘로", bundle: .module),
                onTitle: showToday,
                onNewEvent: kind == .list ? nil : { editor = EditorRequest(.new(day: addDay, input: nil)) },
                onStamp: { stamping = StampRequest(month: visibleMonth) })
            switch kind {
            case .month:
                ScrollView(.vertical) {
                    MonthView(
                        model: month,
                        onDay: { cell in open(day: cell.day, notes: Self.notes(cell.birthdays, cell.holidays)) },
                        onEvent: edit,
                        onLeave: { cell in editor = EditorRequest(.new(day: cell.day, input: Self.leave(cell.day))) })
                }
                .scrollBounceBehavior(.basedOnSize)
            case .week:
                WeekView(
                    model: week,
                    onDay: { day in open(day: day.day, notes: Self.notes(day.birthdays, day.holidays)) },
                    onSlot: { day, hour in
                        open(
                            day: day.day, notes: Self.notes(day.birthdays, day.holidays),
                            range: (hour * 2)..<(hour * 2 + 2))
                    },
                    onEvent: edit)
            case .list:
                AgendaView(
                    model: agenda,
                    onDay: { day in open(day: day.day, notes: Self.notes(day.birthdays, day.holidays)) },
                    onAddToday: { open(day: KoreanCalendar.day(of: now()), notes: []) },
                    onEvent: edit)
            }
            Spacer(minLength: 0)
        }
        .background(GeunalColor.background)
        .sheet(
            item: $entry,
            onDismiss: {
                if let pending = pendingEditor {
                    pendingEditor = nil
                    editor = pending
                }
            }
        ) { request in
            QuickEntrySheet(
                model: QuickEntryModel(
                    day: request.day, notes: request.notes, range: request.range, core: core, now: now),
                actions: actions,
                onMore: { input in
                    pendingEditor = EditorRequest(.new(day: request.day, input: input))
                    entry = nil
                })
        }
        .fullScreenCover(item: $editor) { request in
            EditorView(model: request.model(core: core)) { _ in editor = nil }
        }
        .fullScreenCover(item: $stamping) { request in
            ShiftStampView(model: ShiftStampModel(month: request.month, core: core), core: core) { _ in
                stamping = nil
            }
        }
        .onChange(of: openEvent) { _, event in
            guard let event else { return }
            openEvent = nil
            edit(event)
        }
        .task { await agenda.reload() }
        .onChange(of: kind) { previous, next in
            keepPlace(from: previous, to: next)
        }
        .onChange(of: changes) {
            Task { await reload() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                readAgainOnANewDay()
            }
        }
        // Midnight while the app is in front.
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
            readAgainOnANewDay()
        }
    }

    private func readAgainOnANewDay() {
        let today = KoreanCalendar.day(of: now())
        guard today != shownDay else { return }
        shownDay = today
        Task { await reload() }
    }

    /// Keeps the place when the view changes: the week of the month on screen, or the month of the week on screen.
    private func keepPlace(from previous: Kind, to next: Kind) {
        switch (previous, next) {
        case (.month, .week):
            let visible = month.visibleMonth ?? month.currentMonth
            week.show(day: visible == month.currentMonth ? month.today : visible + "-01")
        case (.week, .month):
            if let start = week.visibleWeek, let middle = KoreanCalendar.adding(days: 3, to: start) {
                month.show(month: KoreanCalendar.month(of: middle))
            }
        default:
            break
        }
    }

    private var heading: (caption: String, title: String) {
        switch kind {
        case .month:
            return DayText.monthHeading(month.visibleMonth ?? month.currentMonth)
        case .week:
            return DayText.weekHeading(start: week.visibleWeek ?? week.currentWeek)
        case .list:
            return (
                DayText.monthHeading(KoreanCalendar.month(of: agenda.today)).caption,
                String(localized: "다가오는 일정", bundle: .module)
            )
        }
    }

    private func showToday() {
        switch kind {
        case .month: month.showToday()
        case .week: week.showToday()
        case .list: break
        }
    }

    private func open(day: String, notes: [String], range: Range<Int>? = nil) {
        entry = EntryRequest(day: day, notes: notes, range: range)
    }

    private func edit(_ event: EventItem) {
        editor = EditorRequest(.existing(eventId: event.eventId, occurrence: event.occurrence))
    }

    /// The month on screen, or this month in the other views.
    private var visibleMonth: String {
        kind == .month ? (month.visibleMonth ?? month.currentMonth) : month.currentMonth
    }

    /// A new event starts today, or on the 1st of another month on screen.
    private var addDay: String {
        let today = KoreanCalendar.day(of: now())
        guard kind == .month, let visible = month.visibleMonth, visible != KoreanCalendar.month(of: today) else {
            return today
        }
        return visible + "-01"
    }

    /// The leave chip fills an all-day '연차' (rust-core.md §6 "공휴일과 연차 다리").
    static func leave(_ day: String) -> EventInput {
        EventInput(
            calendarId: "", title: String(localized: "연차", bundle: .module), allDay: true,
            start: DayTime(day: day, minute: 0), end: DayTime(day: day, minute: 0), recurrence: nil, alert: .inherit,
            note: nil)
    }

    private func reload() async {
        await month.reload()
        await week.reload()
        await agenda.reload()
    }

    /// The line under the quick entry's title: "민수 생일 · 개천절".
    static func notes(_ birthdays: [BirthdayItem], _ holidays: [Holiday]) -> [String] {
        birthdays.map { String(localized: "\($0.name) 생일", bundle: .module) } + holidays.map(\.name)
    }
}

/// What the quick entry opens with.
private struct EntryRequest: Identifiable {
    let id = UUID()
    let day: String
    let notes: [String]
    let range: Range<Int>?
}

/// What the editor opens: a new event, maybe filled in, or an existing one.
private struct EditorRequest: Identifiable {
    enum Target {
        case new(day: String, input: EventInput?)
        case existing(eventId: String, occurrence: String?)
    }

    let id = UUID()
    let target: Target

    init(_ target: Target) {
        self.target = target
    }

    @MainActor
    func model(core: any CoreProtocol) -> EditorModel {
        switch target {
        case .new(let day, let input): EditorModel(newOn: day, input: input, core: core)
        case .existing(let eventId, let occurrence): EditorModel(eventId: eventId, occurrence: occurrence, core: core)
        }
    }
}

private struct StampRequest: Identifiable {
    let id = UUID()
    let month: String
}

/// The header of the three views (boards/Month, Week, List): the year (13 · 600) over the month or range (32), the
/// segment, and "＋". Tapping the month goes back to today.
private struct CalendarHeader: View {
    @Binding var kind: CalendarScreen.Kind
    let caption: String
    let title: String
    let titleHint: Text?
    let onTitle: () -> Void
    let onNewEvent: (() -> Void)?
    let onStamp: () -> Void

    var body: some View {
        HStack(spacing: Space.xxs) {
            Button(action: onTitle) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: caption)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(GeunalColor.secondaryText)
                    Text(verbatim: title)
                        .typeStyle(.largeTitle)
                        .foregroundStyle(GeunalColor.ink)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(titleHint == nil)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            .accessibilityHint(titleHint ?? Text(verbatim: ""))
            .accessibilityIdentifier("calendar.title")
            Spacer(minLength: Space.xs)
            SegmentedControl(
                selection: $kind,
                options: [
                    (value: CalendarScreen.Kind.month, label: Text("월", bundle: .module)),
                    (value: CalendarScreen.Kind.week, label: Text("주", bundle: .module)),
                    (value: CalendarScreen.Kind.list, label: Text("목록", bundle: .module)),
                ]
            )
            if let onNewEvent {
                // ＋ is a menu: a new event or the shift stamp screen (m1.md §2.3).
                Menu {
                    Button(action: onNewEvent) {
                        Label {
                            Text("일정 추가", bundle: .module)
                        } icon: {
                            Image(systemName: "calendar.badge.plus")
                        }
                    }
                    Button(action: onStamp) {
                        Label {
                            Text("교대 찍기", bundle: .module)
                        } icon: {
                            Image(systemName: "square.grid.3x3")
                        }
                    }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 22, weight: .regular))
                        .foregroundStyle(GeunalColor.ink)
                        .frame(width: Metrics.Button.icon, height: Metrics.Button.icon)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(Text("추가", bundle: .module))
                .accessibilityIdentifier("calendar.add")
            }
        }
        .padding(.leading, Space.l)
        .padding(.trailing, Space.xs)
        .frame(minHeight: 64)
    }
}
