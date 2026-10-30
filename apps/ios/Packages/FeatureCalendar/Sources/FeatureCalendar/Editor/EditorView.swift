import DesignSystem
import GeunalCore
import SwiftUI

/// The event editor (m1.md screen 9, boards/Editor), a full-screen modal: 취소 · 일정 편집 · 저장, then the title and
/// calendar, the times, repeat and alert, and "일정 삭제". A device calendar's event opens read-only.
struct EditorView: View {
    @State private var model: EditorModel
    private let onClose: (_ changed: Bool) -> Void
    @State private var asksSaveScope = false
    @State private var asksDelete = false
    @State private var customRepeat = false

    init(model: EditorModel, onClose: @escaping (_ changed: Bool) -> Void) {
        _model = State(initialValue: model)
        self.onClose = onClose
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(spacing: Space.m) {
                    if model.readOnly {
                        NoticeCard(
                            systemImage: "lock",
                            text: Text("이 일정은 기기 캘린더에서 왔어요. 그 앱에서 고칠 수 있어요", bundle: .module),
                            tone: .offline)
                    }
                    if model.problem == .notFound {
                        NoticeCard(
                            systemImage: "exclamationmark.circle",
                            text: Text("일정을 찾지 못했어요. 다른 곳에서 지웠을 수 있어요.", bundle: .module),
                            tone: .problem)
                    }
                    if model.problem == .failed {
                        NoticeCard(
                            systemImage: "exclamationmark.circle",
                            text: Text("저장하지 못했어요. 다시 시도해 주세요", bundle: .module), tone: .problem)
                    }
                    titleCard
                    timeCard
                    repeatCard
                    attachmentsCard
                    if let calendar = model.selectedCalendar {
                        Text("‘\(calendar.name)’ 캘린더를 공유하는 그룹에는 이 시간이 일정 있음으로만 보여요. 제목과 첨부는 보이지 않아요.", bundle: .module)
                            .font(.system(size: 13))
                            .lineSpacing(3)
                            .foregroundStyle(GeunalColor.secondaryText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    }
                    if !model.isNew && !model.readOnly {
                        Button {
                            asksDelete = true
                        } label: {
                            Text("일정 삭제", bundle: .module)
                                .font(.system(size: 17))
                                .foregroundStyle(GeunalColor.sunday)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.input))
                        }
                        .buttonStyle(.pressable)
                        .accessibilityIdentifier("editor.delete")
                    }
                }
                .padding(.horizontal, Space.m)
                .padding(.vertical, Space.s)
            }
            .disabled(model.readOnly || !model.hasLoaded)
        }
        .background(GeunalColor.groupedBackground)
        .task { await model.load() }
        .sheet(isPresented: $asksSaveScope) {
            ScopeSheet(
                title: Text("반복 일정을 바꿀까요?", bundle: .module),
                thisOnly: Text("이 일정만", bundle: .module), following: Text("이후 모두", bundle: .module),
                destructive: false
            ) { scope in
                asksSaveScope = false
                Task { await save(scope: scope) }
            }
        }
        .sheet(isPresented: $asksDelete) {
            if model.asksScope {
                ScopeSheet(
                    title: Text("반복 일정을 삭제할까요?", bundle: .module),
                    thisOnly: Text("이 일정만 삭제", bundle: .module), following: Text("이후 모두 삭제", bundle: .module),
                    destructive: true
                ) { scope in
                    asksDelete = false
                    Task { await delete(scope: scope) }
                }
            } else {
                DeleteSheet { confirmed in
                    asksDelete = false
                    if confirmed {
                        Task { await delete(scope: .thisAndFollowing) }
                    }
                }
            }
        }
        .sheet(isPresented: $customRepeat) {
            CustomRepeatSheet(recurrence: model.recurrence, startDay: model.startDay) { recurrence in
                model.recurrence = recurrence
                customRepeat = false
            }
        }
    }

    private var topBar: some View {
        HStack {
            Button {
                onClose(false)
            } label: {
                Text(model.readOnly ? "닫기" : "취소", bundle: .module)
                    .font(.system(size: 17))
                    .foregroundStyle(GeunalColor.secondaryText)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("editor.cancel")
            Spacer()
            Text(model.isNew ? "새 일정" : "일정 편집", bundle: .module)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            if model.readOnly {
                Color.clear.frame(width: 64, height: 44)
            } else {
                Button {
                    if model.asksScope {
                        asksSaveScope = true
                    } else {
                        Task { await save(scope: .thisAndFollowing) }
                    }
                } label: {
                    Text("저장", bundle: .module)
                }
                .buttonStyle(.geunal(.prominentPill, height: Metrics.Button.smallPill))
                .disabled(!model.canSave)
                .accessibilityIdentifier("editor.save")
            }
        }
        .padding(.leading, Space.xs)
        .padding(.trailing, Space.s)
        .frame(height: 56)
    }

    private var titleCard: some View {
        VStack(spacing: 0) {
            TextField(text: $model.title) {
                Text("제목", bundle: .module)
            }
            .font(.system(size: 20, weight: .semibold))
            .padding(.horizontal, Space.m)
            .frame(height: 56)
            .accessibilityIdentifier("editor.title")
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            Menu {
                Picker(selection: $model.calendarId) {
                    ForEach(model.ownCalendars, id: \.id) { calendar in
                        Text(verbatim: calendar.name).tag(Optional(calendar.id))
                    }
                } label: {
                    Text("캘린더", bundle: .module)
                }
            } label: {
                HStack(spacing: Space.xs) {
                    Text("캘린더", bundle: .module)
                        .foregroundStyle(GeunalColor.ink)
                    Spacer()
                    Circle()
                        .fill(CalendarColors.marker(model.selectedCalendar?.color))
                        .frame(width: 10, height: 10)
                    Text(verbatim: model.selectedCalendar?.name ?? "")
                        .foregroundStyle(GeunalColor.secondaryText)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(GeunalColor.tertiaryText)
                }
                .font(.system(size: 17))
                .padding(.horizontal, Space.m)
                .frame(height: 48)
                .contentShape(Rectangle())
            }
            .disabled(model.readOnly)
            .accessibilityIdentifier("editor.calendar")
        }
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.input))
    }

    private var timeCard: some View {
        VStack(spacing: 0) {
            Toggle(isOn: Binding(get: { model.allDay }, set: { model.setAllDay($0) })) {
                Text("종일", bundle: .module)
                    .font(.system(size: 17))
                    .foregroundStyle(GeunalColor.ink)
            }
            .tint(GeunalColor.ink)
            .padding(.horizontal, Space.m)
            .frame(height: 48)
            .accessibilityIdentifier("editor.allDay")
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            timeRow(
                Text("시작", bundle: .module),
                selection: Binding(
                    get: { Self.date(model.startDay, model.startMinute) },
                    set: { date in
                        let (day, minute) = Self.dayAndMinute(date)
                        model.setStart(day: day, minute: minute)
                    }),
                range: nil, identifier: "editor.start")
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            timeRow(
                Text("끝", bundle: .module),
                selection: Binding(
                    get: { Self.date(model.endDay, model.endMinute) },
                    set: { date in
                        let (day, minute) = Self.dayAndMinute(date)
                        model.setEnd(day: day, minute: minute)
                    }),
                range: Self.date(model.startDay, model.allDay ? 0 : model.startMinute)...,
                identifier: "editor.end")
        }
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.input))
    }

    private func timeRow(
        _ title: Text, selection: Binding<Date>, range: PartialRangeFrom<Date>?, identifier: String
    ) -> some View {
        HStack {
            title
                .font(.system(size: 17))
                .foregroundStyle(GeunalColor.ink)
            Spacer()
            Group {
                if let range {
                    DatePicker(
                        selection: selection, in: range,
                        displayedComponents: model.allDay ? [.date] : [.date, .hourAndMinute]
                    ) { title }
                } else {
                    DatePicker(
                        selection: selection, displayedComponents: model.allDay ? [.date] : [.date, .hourAndMinute]
                    ) { title }
                }
            }
            .labelsHidden()
            .datePickerStyle(.compact)
            // The core's days are Korean days whatever the phone's own time zone (rust-core.md §3).
            .environment(\.timeZone, KoreanCalendar.timeZone)
            .environment(\.calendar, KoreanCalendar.calendar)
            .accessibilityIdentifier(identifier)
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: 48)
    }

    private var repeatCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                Text("반복", bundle: .module)
                    .font(.system(size: 17))
                    .foregroundStyle(GeunalColor.ink)
                FlowLayout(spacing: 6, rowSpacing: 6) {
                    ForEach(repeatChoices.indices, id: \.self) { index in
                        let choice = repeatChoices[index]
                        OutlineChip(
                            Text(verbatim: choice.label), isOn: choice.recurrence == model.recurrence
                        ) {
                            model.recurrence = choice.recurrence
                        }
                    }
                    OutlineChip(Text("직접 정하기", bundle: .module), isOn: false, muted: true) {
                        customRepeat = true
                    }
                    .accessibilityIdentifier("editor.customRepeat")
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, 14)
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            Menu {
                Picker(selection: $model.alert) {
                    ForEach(AlertText.choices(allDay: model.allDay), id: \.self) { rule in
                        Text(verbatim: AlertText.describe(rule, allDay: model.allDay)).tag(rule)
                    }
                } label: {
                    Text("알림", bundle: .module)
                }
            } label: {
                HStack(spacing: Space.xs) {
                    Text("알림", bundle: .module)
                        .foregroundStyle(GeunalColor.ink)
                    Spacer()
                    Text(verbatim: alertValue)
                        .foregroundStyle(GeunalColor.secondaryText)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(GeunalColor.tertiaryText)
                }
                .font(.system(size: 17))
                .padding(.horizontal, Space.m)
                .frame(height: 48)
                .contentShape(Rectangle())
            }
            .accessibilityIdentifier("editor.alert")
        }
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.input))
    }

    /// Attachments arrive with M3; the card says so instead of offering a button that does nothing.
    private var attachmentsCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text("사진·파일", bundle: .module)
                    .font(.system(size: 17))
                    .foregroundStyle(GeunalColor.tertiaryText)
                Text(verbatim: "M3")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            Text("3개까지 · 파일당 10MB · 올린 날부터 1년 보관", bundle: .module)
                .font(.system(size: 12))
                .foregroundStyle(GeunalColor.secondaryText)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Space.m)
        .padding(.vertical, 14)
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.input))
        .accessibilityElement(children: .combine)
    }

    /// The chips of m1.md screen 9: none, weekly on the start's weekday, daily, monthly, yearly, and the event's own
    /// rule when it is none of those.
    private var repeatChoices: [(label: String, recurrence: Recurrence?)] {
        func rule(_ frequency: Frequency) -> Recurrence {
            Recurrence(frequency: frequency, interval: 1, weekdays: [], until: nil, count: nil)
        }
        var choices: [(label: String, recurrence: Recurrence?)] = [
            (String(localized: "안 함", bundle: .module), nil)
        ]
        for frequency in [Frequency.weekly, .daily, .monthly, .yearly] {
            let recurrence = rule(frequency)
            choices.append((RecurrenceText.describe(recurrence, startDay: model.startDay), recurrence))
        }
        if let own = model.recurrence, !choices.contains(where: { $0.recurrence == own }) {
            choices.insert((RecurrenceText.describe(own, startDay: model.startDay), own), at: 2)
        }
        return choices
    }

    private var alertValue: String {
        if case .inherit = model.alert, let effective = model.effectiveAlert {
            let value = AlertText.describe(minutes: effective, allDay: model.allDay)
            return String(localized: "캘린더 기본 · \(value)", bundle: .module)
        }
        return AlertText.describe(model.alert, allDay: model.allDay)
    }

    private func save(scope: EditScope) async {
        if await model.save(scope: scope) {
            onClose(true)
        }
    }

    private func delete(scope: EditScope) async {
        if await model.delete(scope: scope) {
            onClose(true)
        }
    }

    // MARK: Korean days and minutes as dates for the pickers

    static func date(_ day: String, _ minute: Int) -> Date {
        (KoreanCalendar.start(of: day) ?? Date()).addingTimeInterval(TimeInterval(minute * 60))
    }

    static func dayAndMinute(_ date: Date) -> (String, Int) {
        let day = KoreanCalendar.day(of: date)
        let midnight = KoreanCalendar.start(of: day) ?? date
        return (day, Int(date.timeIntervalSince(midnight) / 60))
    }
}

/// An outlined chip (boards/Editor: the repeat choices), ink when chosen.
private struct OutlineChip: View {
    private let label: Text
    private let isOn: Bool
    private let muted: Bool
    private let action: () -> Void

    init(_ label: Text, isOn: Bool, muted: Bool = false, action: @escaping () -> Void) {
        self.label = label
        self.isOn = isOn
        self.muted = muted
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            label
                .typeStyle(.pillLabel)
                .foregroundStyle(isOn ? GeunalColor.background : (muted ? GeunalColor.secondaryText : GeunalColor.ink))
                .padding(.horizontal, Space.s)
                .frame(minHeight: Metrics.Chip.select)
                .background {
                    if isOn {
                        Capsule().fill(GeunalColor.ink)
                    } else {
                        Capsule().strokeBorder(GeunalColor.separator, lineWidth: 1)
                    }
                }
                .frame(minHeight: Metrics.minHitTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }
}
