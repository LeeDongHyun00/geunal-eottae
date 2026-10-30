import DesignSystem
import GeunalCore
import SwiftUI

/// The shift stamp screen (m1.md screen 10, boards/Shift), a full-screen modal: 취소 · 교대 찍기 · 완료, the month
/// with the stamped templates, and the template panel. Taps stay a draft until "완료".
struct ShiftStampView: View {
    @State private var model: ShiftStampModel
    private let core: any CoreProtocol
    private let onClose: (_ changed: Bool) -> Void
    @State private var editing: TemplateRequest?
    @State private var stamps = 0

    init(model: ShiftStampModel, core: any CoreProtocol, onClose: @escaping (_ changed: Bool) -> Void) {
        _model = State(initialValue: model)
        self.core = core
        self.onClose = onClose
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            monthBar
            WeekdayRow()
                .padding(.horizontal, Space.xs)
            ScrollView {
                grid
                    .padding(.horizontal, Space.xs)
            }
            panel
        }
        .background(GeunalColor.background)
        .task { await model.load() }
        .sensoryFeedback(.selection, trigger: stamps)
        .sheet(item: $editing) { request in
            TemplateEditSheet(
                model: TemplateEditModel(template: request.template, core: core),
                onSaved: { id in
                    editing = nil
                    Task { await model.templateSaved(id) }
                },
                onDeleted: { id in
                    editing = nil
                    Task { await model.templateDeleted(id) }
                })
        }
    }

    private var topBar: some View {
        HStack {
            Button {
                onClose(false)
            } label: {
                Text("취소", bundle: .module)
                    .font(.system(size: 17))
                    .foregroundStyle(GeunalColor.secondaryText)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
            Spacer()
            Text("교대 찍기", bundle: .module)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button {
                Task {
                    if await model.commit() {
                        onClose(true)
                    }
                }
            } label: {
                Text("완료", bundle: .module)
            }
            .buttonStyle(.geunal(.prominentPill, height: Metrics.Button.smallPill))
            .disabled(!model.hasChanges || model.isSaving)
            .accessibilityIdentifier("shift.done")
        }
        .padding(.leading, Space.xs)
        .padding(.trailing, Space.s)
        .frame(height: 56)
    }

    private var monthBar: some View {
        HStack(spacing: 0) {
            IconButton(systemImage: "chevron.left", label: Text("이전 달", bundle: .module)) {
                Task { await model.step(months: -1) }
            }
            Text(verbatim: DayText.monthHeading(model.month).title)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .frame(minWidth: 56)
            IconButton(systemImage: "chevron.right", label: Text("다음 달", bundle: .module)) {
                Task { await model.step(months: 1) }
            }
            Spacer()
        }
        .padding(.horizontal, Space.xs)
    }

    private var grid: some View {
        VStack(spacing: 0) {
            ForEach(model.weeks, id: \.self) { week in
                HStack(spacing: 0) {
                    ForEach(week, id: \.self) { day in
                        StampCell(
                            day: day, inMonth: KoreanCalendar.month(of: day) == model.month,
                            templates: model.templates(on: day)
                        ) {
                            model.tap(day: day)
                            stamps += 1
                        }
                        .disabled(model.selectedTemplate == nil)
                    }
                }
                .overlay(alignment: .top) {
                    Rectangle().fill(GeunalColor.separator).frame(height: 1)
                }
            }
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            if model.hasLoaded && model.templates.isEmpty {
                VStack(spacing: Space.s) {
                    Text("첫 템플릿을 만들어요", bundle: .module)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(GeunalColor.ink)
                    Button {
                        editing = TemplateRequest(template: nil)
                    } label: {
                        Text("새 템플릿", bundle: .module)
                    }
                    .buttonStyle(.geunal(.primary))
                    .accessibilityIdentifier("shift.firstTemplate")
                }
                .frame(maxWidth: .infinity)
            } else {
                HStack(alignment: .firstTextBaseline) {
                    Text("템플릿을 고르고 날짜를 누르세요", bundle: .module)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(GeunalColor.ink)
                    Spacer()
                    Text(verbatim: countText)
                        .font(.system(size: 13))
                        .foregroundStyle(GeunalColor.secondaryText)
                }
                .padding(.horizontal, 4)
                LazyVGrid(
                    columns: [GridItem(.flexible(), spacing: Space.xs), GridItem(.flexible())], spacing: Space.xs
                ) {
                    ForEach(model.templates, id: \.id) { template in
                        TemplateCard(template: template, selected: template.id == model.selectedTemplateId) {
                            model.selectedTemplateId = template.id
                        } onEdit: {
                            editing = TemplateRequest(template: template)
                        }
                    }
                    Button {
                        editing = TemplateRequest(template: nil)
                    } label: {
                        Label {
                            Text("새 템플릿", bundle: .module)
                        } icon: {
                            Image(systemName: "plus")
                        }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(GeunalColor.secondaryText)
                        .frame(maxWidth: .infinity, minHeight: 60)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16)
                                .strokeBorder(
                                    GeunalColor.tertiaryText, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                        )
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.pressable)
                    .accessibilityIdentifier("shift.newTemplate")
                }
                if let calendar = calendarName {
                    Text("찍은 근무는 ‘\(calendar)’ 캘린더의 일반 일정이 돼요. 같은 날을 한 번 더 누르면 빠져요.", bundle: .module)
                        .font(.system(size: 13))
                        .lineSpacing(3)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .padding(.horizontal, 4)
                }
            }
        }
        .padding(.horizontal, Space.m)
        .padding(.top, 18)
        .padding(.bottom, Space.l)
        .background {
            UnevenRoundedRectangle(topLeadingRadius: 24, topTrailingRadius: 24)
                .fill(GeunalColor.background)
                .shadow(color: .black.opacity(0.10), radius: 16, y: -8)
                .ignoresSafeArea(edges: .bottom)
        }
    }

    /// "11월 9번".
    private var countText: String {
        let month = DayText.monthHeading(model.month).title
        return String(localized: "\(month) \(model.countThisMonth)번", bundle: .module)
    }

    /// The calendar the chosen template stamps into.
    private var calendarName: String? {
        guard let template = model.selectedTemplate else { return nil }
        return model.calendarName(of: template)
    }
}

private struct TemplateRequest: Identifiable {
    let id = UUID()
    let template: ShiftTemplate?
}

/// A day of the stamp grid: the date and the stamped template as a chip in its color.
private struct StampCell: View {
    let day: String
    let inMonth: Bool
    let templates: [ShiftTemplate]
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Text(verbatim: String(KoreanCalendar.parse(day)?.day ?? 0))
                    .font(.system(size: 13, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(
                        inMonth
                            ? DayColors.date(weekday: KoreanCalendar.weekday(of: day) ?? 2, holiday: false)
                            : GeunalColor.tertiaryText)
                ForEach(templates, id: \.id) { template in
                    let colors = ShiftColors.chip(template)
                    Text(verbatim: template.name)
                        .font(.system(size: 11, weight: .semibold))
                        .lineLimit(1)
                        .foregroundStyle(colors.text)
                        .frame(maxWidth: .infinity, minHeight: 18)
                        .background(colors.fill, in: RoundedRectangle(cornerRadius: Radius.strip))
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 5)
            .padding(.horizontal, 2)
            .frame(maxWidth: .infinity, minHeight: 60, alignment: .top)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityIdentifier("shift.day.\(day)")
    }

    private var label: String {
        ([DayText.title(day, today: day)] + templates.map(\.name)).joined(separator: ", ")
    }
}

/// A template in the panel (boards/Shift): its color bar, name and hours; ink ring and a check when chosen. A long
/// press opens it for editing.
private struct TemplateCard: View {
    let template: ShiftTemplate
    let selected: Bool
    let onSelect: () -> Void
    let onEdit: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: Radius.strip)
                    .fill(ShiftColors.chip(template).fill)
                    .frame(width: 12, height: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: template.name)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(GeunalColor.ink)
                        .lineLimit(1)
                    Text(verbatim: DayText.range(start: template.startMinute, end: template.endMinute))
                        .font(.system(size: 13))
                        .monospacedDigit()
                        .foregroundStyle(GeunalColor.secondaryText)
                }
                Spacer(minLength: 0)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(GeunalColor.ink)
                }
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, minHeight: 60)
            .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(selected ? GeunalColor.ink : GeunalColor.separator, lineWidth: selected ? 2 : 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in onEdit() })
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityAction(named: Text("템플릿 편집", bundle: .module), onEdit)
        .accessibilityIdentifier("shift.template.\(template.name)")
    }
}

/// Template colors: the calendar color in the template's tint (1 lightest – 5 darkest), white text from tint 4.
enum ShiftColors {
    static func chip(_ template: ShiftTemplate) -> (fill: Color, text: Color) {
        let tint = min(max(Int(template.tint), 1), 5)
        return (template.color.groupColor.tint(tint), tint >= 4 ? .white : GeunalColor.ink)
    }
}
