import DesignSystem
import GeunalCore
import SwiftUI

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
    @State private var month: MonthModel
    @State private var week: WeekModel
    @State private var agenda: AgendaModel
    @State private var entry: EntryRequest?
    @AppStorage("app.geunal.calendar.view") private var kind: Kind = .month
    private let actions: EventActions
    private let now: @Sendable () -> Date

    /// `changes` counts the core's calendar changes; each new value reads the views again.
    public init(
        core: any CoreProtocol, toasts: ToastCenter, changes: Int, now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.core = core
        self.changes = changes
        self.now = now
        actions = EventActions(core: core, toasts: toasts)
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
                onAdd: kind == .list ? nil : { open(day: KoreanCalendar.day(of: now()), notes: []) })
            switch kind {
            case .month:
                ScrollView(.vertical) {
                    MonthView(model: month) { cell in
                        open(day: cell.day, notes: Self.notes(cell.birthdays, cell.holidays))
                    }
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
                    })
            case .list:
                AgendaView(
                    model: agenda,
                    onDay: { day in open(day: day.day, notes: Self.notes(day.birthdays, day.holidays)) },
                    onAddToday: { open(day: KoreanCalendar.day(of: now()), notes: []) })
            }
            Spacer(minLength: 0)
        }
        .background(GeunalColor.background)
        .sheet(item: $entry) { request in
            QuickEntrySheet(
                model: QuickEntryModel(
                    day: request.day, notes: request.notes, range: request.range, core: core, now: now),
                actions: actions)
        }
        .task { await agenda.reload() }
        .onChange(of: kind) { previous, next in
            keepPlace(from: previous, to: next)
        }
        .onChange(of: changes) {
            Task { await reload() }
        }
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

/// The header of the three views (boards/Month, Week, List): the year (13 · 600) over the month or range (32), the
/// segment, and "＋". Tapping the month goes back to today.
private struct CalendarHeader: View {
    @Binding var kind: CalendarScreen.Kind
    let caption: String
    let title: String
    let titleHint: Text?
    let onTitle: () -> Void
    let onAdd: (() -> Void)?

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
            if let onAdd {
                IconButton(systemImage: "plus", label: Text("일정 추가", bundle: .module), action: onAdd)
                    .accessibilityIdentifier("calendar.add")
            }
        }
        .padding(.leading, Space.l)
        .padding(.trailing, Space.xs)
        .frame(minHeight: 64)
    }
}
