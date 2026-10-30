import DesignSystem
import GeunalCore
import SwiftUI

/// The template sheet (m1.md screen 18, boards/TemplateEdit): the preview card, the name, start and end, calendar and
/// alert, the midnight note, "저장" and the red "템플릿 삭제".
struct TemplateEditSheet: View {
    @State private var model: TemplateEditModel
    private let onSaved: (String) -> Void
    private let onDeleted: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    init(model: TemplateEditModel, onSaved: @escaping (String) -> Void, onDeleted: @escaping (String) -> Void) {
        _model = State(initialValue: model)
        self.onSaved = onSaved
        self.onDeleted = onDeleted
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                header
                preview
                nameField
                rows
                Text("자정을 넘기는 근무(22:00–02:00)는 다음 날까지 한 일정으로 들어가요.", bundle: .module)
                    .font(.system(size: 14, weight: model.crossesMidnight ? .semibold : .regular))
                    .lineSpacing(3)
                    .foregroundStyle(model.crossesMidnight ? GeunalColor.ink : GeunalColor.secondaryText)
                    .padding(.horizontal, Space.xs)
                    .accessibilityIdentifier("template.midnight")
                plusRow
                if model.failed {
                    NoticeCard(
                        systemImage: "exclamationmark.circle",
                        text: Text("저장하지 못했어요. 다시 시도해 주세요", bundle: .module), tone: .problem)
                }
                Button {
                    Task {
                        if let id = await model.save() {
                            onSaved(id)
                        }
                    }
                } label: {
                    Text("저장", bundle: .module)
                }
                .buttonStyle(.geunal(.primary))
                .disabled(!model.canSave)
                .accessibilityIdentifier("template.save")
                if let id = model.templateId {
                    Button {
                        Task {
                            if await model.delete() {
                                onDeleted(id)
                            }
                        }
                    } label: {
                        Text("템플릿 삭제 · 이미 찍은 근무는 남아요", bundle: .module)
                    }
                    .buttonStyle(.geunal(.destructive))
                    .accessibilityIdentifier("template.delete")
                }
            }
            .padding(.horizontal, Metrics.Sheet.Padding.side)
            .padding(.top, Space.l)
            .padding(.bottom, Space.l)
        }
        .task { await model.load() }
        .sheetChrome(height: nil)
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text("템플릿 편집", bundle: .module)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("찍은 근무는 고른 캘린더의 일반 일정이 돼요.", bundle: .module)
                    .font(.system(size: 14))
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            Spacer()
            IconButton(systemImage: "xmark", label: Text("닫기", bundle: .module)) {
                dismiss()
            }
            .padding(.top, -6)
            .padding(.trailing, -12)
        }
    }

    /// "달력에 이렇게 찍혀요": the bar in the template's color, the name, the hours and the length.
    private var preview: some View {
        HStack(spacing: Space.s) {
            RoundedRectangle(cornerRadius: Radius.strip)
                .fill(previewColor)
                .frame(width: 8, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: model.trimmedName.isEmpty ? "—" : model.trimmedName)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(GeunalColor.ink)
                Text(verbatim: hoursText)
                    .font(.system(size: 15))
                    .monospacedDigit()
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            Spacer()
            Text("달력에 이렇게 찍혀요", bundle: .module)
                .font(.system(size: 13))
                .foregroundStyle(GeunalColor.secondaryText)
        }
        .padding(Space.m)
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .combine)
    }

    private var nameField: some View {
        HStack(spacing: Space.s) {
            Text("이름", bundle: .module)
                .foregroundStyle(GeunalColor.secondaryText)
            TextField(text: $model.name) {
                Text("오픈", bundle: .module)
            }
            .foregroundStyle(GeunalColor.ink)
            .accessibilityIdentifier("template.name")
        }
        .font(.system(size: 17))
        .padding(.horizontal, Space.m)
        .frame(height: 48)
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.input))
    }

    private var rows: some View {
        VStack(spacing: 0) {
            timeRow(Text("시작", bundle: .module), minute: $model.startMinute, identifier: "template.start")
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            timeRow(Text("끝", bundle: .module), minute: $model.endMinute, identifier: "template.end")
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            Menu {
                Picker(selection: $model.calendarId) {
                    Text("알바(새로 만들어요)", bundle: .module).tag(String?.none)
                    ForEach(model.ownCalendars, id: \.id) { calendar in
                        Text(verbatim: calendar.name).tag(Optional(calendar.id))
                    }
                } label: {
                    Text("캘린더", bundle: .module)
                }
            } label: {
                SettingsRow(Text("캘린더", bundle: .module), value: Text(verbatim: calendarValue))
            }
            .accessibilityIdentifier("template.calendar")
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            Menu {
                Picker(selection: $model.alert) {
                    ForEach(AlertText.choices(allDay: false), id: \.self) { rule in
                        Text(verbatim: AlertText.describe(rule, allDay: false)).tag(rule)
                    }
                } label: {
                    Text("알림", bundle: .module)
                }
            } label: {
                SettingsRow(
                    Text("알림", bundle: .module), value: Text(verbatim: AlertText.describe(model.alert, allDay: false)))
            }
            .accessibilityIdentifier("template.alert")
        }
    }

    private func timeRow(_ title: Text, minute: Binding<Int>, identifier: String) -> some View {
        HStack {
            title
                .typeStyle(.body)
                .foregroundStyle(GeunalColor.ink)
            Spacer()
            DatePicker(
                selection: Binding(
                    get: { Self.date(minute.wrappedValue) },
                    set: { minute.wrappedValue = Self.minute($0) }),
                displayedComponents: [.hourAndMinute]
            ) { title }
            .labelsHidden()
            .datePickerStyle(.compact)
            .environment(\.timeZone, KoreanCalendar.timeZone)
            .accessibilityIdentifier(identifier)
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: Metrics.Row.setting)
    }

    /// The Plus feature the board shows: hours and expected pay of the template's shifts, not in M1.
    private var plusRow: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text("근무 시간 집계", bundle: .module)
                    .typeStyle(.body)
                    .foregroundStyle(GeunalColor.tertiaryText)
                Text("이 템플릿으로 찍은 시간의 합계와 예상 급여. 그날어때 플러스", bundle: .module)
                    .font(.system(size: 14))
                    .foregroundStyle(GeunalColor.tertiaryText)
            }
            Spacer()
            StatusBadge(Text("플러스", bundle: .module), tone: .group(.grape))
        }
        .padding(.horizontal, Space.m)
        .accessibilityElement(children: .combine)
    }

    private var previewColor: Color {
        guard let palette = model.previewColor else { return GroupColor.mint.tint(3) }
        return palette.groupColor.tint(min(max(Int(model.tint), 1), 5))
    }

    /// "08:00–13:00 · 5시간", "22:00–02:00 · 4시간 30분".
    private var hoursText: String {
        let range = DayText.range(start: UInt16(model.startMinute), end: UInt16(model.endMinute))
        let hours = model.lengthMinutes / 60
        let minutes = model.lengthMinutes % 60
        let length =
            minutes == 0
            ? String(localized: "\(hours)시간", bundle: .module)
            : String(localized: "\(hours)시간 \(minutes)분", bundle: .module)
        return "\(range) · \(length)"
    }

    private var calendarValue: String {
        guard let calendar = model.selectedCalendar else { return String(localized: "알바", bundle: .module) }
        let color = calendar.color?.groupColor.name ?? ""
        return color.isEmpty ? calendar.name : "\(calendar.name) · \(color)"
    }

    static func date(_ minute: Int) -> Date {
        (KoreanCalendar.start(of: "2000-01-01") ?? Date()).addingTimeInterval(TimeInterval(minute * 60))
    }

    static func minute(_ date: Date) -> Int {
        let parts = KoreanCalendar.calendar.dateComponents([.hour, .minute], from: date)
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }
}
