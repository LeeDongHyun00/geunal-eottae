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
    @Binding private var opening: Opening?
    @State private var month: MonthModel
    @State private var week: WeekModel
    @State private var agenda: AgendaModel
    @State private var entry: EntryRequest?
    @State private var editor: EditorRequest?
    /// "자세히" closes the quick entry first; the editor follows once the sheet is gone.
    @State private var pendingEditor: EditorRequest?
    /// The week view's overlap group whose sheet is up (m1.md decision 44).
    @State private var overlap: OverlapRequest?
    /// "이날 일정 모두 보기" waits for the overlap sheet to go: SwiftUI drops a sheet asked for while one is closing.
    @State private var pendingEntry: EntryRequest?
    @State private var stamping: StampRequest?
    /// The Korean day the views were read for. An app left open overnight, or opened again the next day, reads them
    /// again for the new today.
    @State private var shownDay: String
    @AppStorage("app.geunal.calendar.view") private var kind: Kind = .month
    @Environment(\.scenePhase) private var scenePhase
    private let actions: EventActions
    private let recoveryBanner: RecoveryBanner?
    private let now: @Sendable () -> Date

    /// The banner under the month view's header (m1.md screen 4).
    public enum RecoveryBanner {
        /// The code is not written down yet: "저장" opens it.
        case unsaved(() -> Void)
        /// "나중에 넣을게요" left the account's backup waiting for the code: "넣기" opens the restore.
        case pending(() -> Void)
    }

    /// What to open from outside the calendar (m1.md §2.6): the next-up bar's or an alert's event in the editor, a
    /// leave suggestion's week in the month view.
    public enum Opening: Equatable, Sendable {
        case event(eventId: String, occurrence: String?)
        case week(day: String)
    }

    /// `changes` counts the core's calendar changes; each new value reads the views again. What is set in `opening`
    /// opens, also when it was set before the screen appeared. `recoveryBanner` puts the recovery code banner under
    /// the month view's header.
    public init(
        core: any CoreProtocol, toasts: ToastCenter, changes: Int, opening: Binding<Opening?> = .constant(nil),
        recoveryBanner: RecoveryBanner? = nil, now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.core = core
        self.changes = changes
        _opening = opening
        self.recoveryBanner = recoveryBanner
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
            if kind == .month, let recoveryBanner {
                banner(recoveryBanner)
                    .padding(.horizontal, Space.m)
                    .padding(.bottom, Space.s)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("month.recoveryBanner")
            }
            switch kind {
            case .month:
                ScrollView(.vertical) {
                    MonthView(
                        model: month,
                        onDay: { cell in
                            open(day: cell.day, notes: Self.notes(cell.birthdays, cell.holidays), events: cell.events)
                        },
                        onEvent: edit,
                        onLeave: { cell in editor = EditorRequest(.new(day: cell.day, input: Self.leave(cell.day))) })
                }
                .scrollBounceBehavior(.basedOnSize)
            case .week:
                WeekView(
                    model: week,
                    onDay: { day in
                        open(day: day.day, notes: Self.notes(day.birthdays, day.holidays), events: Self.events(day))
                    },
                    onSlot: { day, hour in
                        open(
                            day: day.day, notes: Self.notes(day.birthdays, day.holidays), events: Self.events(day),
                            range: (hour * 2)..<(hour * 2 + 2))
                    },
                    onEvent: edit,
                    onGroup: { group, day in overlap = OverlapRequest(group: group, day: day) })
            case .list:
                AgendaView(
                    model: agenda,
                    onDay: { day in
                        open(day: day.day, notes: Self.notes(day.birthdays, day.holidays), events: day.events)
                    },
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
                    day: request.day, notes: request.notes, events: request.events, range: request.range, core: core,
                    now: now),
                actions: actions,
                onMore: { input in
                    pendingEditor = EditorRequest(.new(day: request.day, input: input))
                    entry = nil
                },
                onEvent: { event in
                    pendingEditor = EditorRequest(.existing(eventId: event.eventId, occurrence: event.occurrence))
                    entry = nil
                })
        }
        .sheet(
            item: $overlap,
            onDismiss: {
                if let pending = pendingEditor {
                    pendingEditor = nil
                    editor = pending
                } else if let pending = pendingEntry {
                    pendingEntry = nil
                    entry = pending
                }
            }
        ) { request in
            OverlapSheet(
                group: request.group, today: week.today,
                onEvent: { event in
                    pendingEditor = EditorRequest(.existing(eventId: event.eventId, occurrence: event.occurrence))
                    overlap = nil
                },
                onDay: {
                    let day = request.day
                    pendingEntry = EntryRequest(
                        day: day.day, notes: Self.notes(day.birthdays, day.holidays), events: Self.events(day),
                        range: nil)
                    overlap = nil
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
        .onChange(of: opening, initial: true) { _, opening in
            guard let opening else { return }
            self.opening = nil
            open(opening)
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

    private func banner(_ banner: RecoveryBanner) -> Banner {
        switch banner {
        case .unsaved(let action):
            Banner(
                systemImage: "lock", title: Text("복구 코드를 저장해 두세요", bundle: .module),
                detail: Text("첫 그룹에 들어가기 전까지 이 배너로 알려요.", bundle: .module),
                actionLabel: Text("저장", bundle: .module), action: action)
        case .pending(let action):
            Banner(
                systemImage: "key", title: Text("복구 코드를 넣으면 백업을 가져와요", bundle: .module),
                detail: Text("넣을 때까지 백업은 쉬어요.", bundle: .module),
                actionLabel: Text("넣기", bundle: .module), action: action)
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

    private func open(day: String, notes: [String], events: [EventItem]? = [], range: Range<Int>? = nil) {
        entry = EntryRequest(day: day, notes: notes, events: events, range: range)
    }

    private func edit(_ event: EventItem) {
        editor = EditorRequest(.existing(eventId: event.eventId, occurrence: event.occurrence))
    }

    private func open(_ opening: Opening) {
        switch opening {
        case .event(let eventId, let occurrence):
            editor = EditorRequest(.existing(eventId: eventId, occurrence: occurrence))
        case .week(let day):
            // Coming from the week view, keeping the place shows the month of that week, as this does.
            week.show(day: day)
            month.show(month: KoreanCalendar.month(of: day))
            kind = .month
        }
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

    /// A week column's events for the quick entry: all-day ones first, then the timed blocks in their order. When the
    /// column hid some behind "+N" (they are not in the week's answer), `nil` has the sheet read the day's month cell.
    static func events(_ day: WeekDay) -> [EventItem]? {
        day.hidden > 0 ? nil : day.allDay + day.timed.map(\.event)
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
    let events: [EventItem]?
    let range: Range<Int>?
}

/// The overlap group the week view's sheet shows, with its day for "이날 일정 모두 보기".
private struct OverlapRequest: Identifiable {
    let id = UUID()
    let group: OverlapGroup
    let day: WeekDay
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
