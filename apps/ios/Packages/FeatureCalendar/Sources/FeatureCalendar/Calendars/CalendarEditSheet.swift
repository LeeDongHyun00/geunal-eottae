import DesignSystem
import GeunalCore
import SwiftUI

/// 캘린더 편집 (m1.md screen 16, boards/CalendarEdit): the name, twelve colors in six columns, the soft switch, the
/// default alert, "저장", and the red "캘린더 삭제 · 일정 7개도 지워져요".
struct CalendarEditSheet: View {
    @State private var model: CalendarEditModel
    @State private var confirmsDelete = false
    private let onDone: () -> Void
    @Environment(\.dismiss) private var dismiss

    init(model: CalendarEditModel, onDone: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.onDone = onDone
    }

    private let columns = Array(repeating: GridItem(.flexible(), spacing: Space.s), count: 6)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                header
                nameField
                LazyVGrid(columns: columns, spacing: Space.s) {
                    ForEach(CalendarEditModel.colors, id: \.self) { color in
                        ColorSwatch(color: color, selected: model.color == color) {
                            model.color = color
                        }
                    }
                }
                VStack(spacing: 0) {
                    GeunalToggle(
                        isOn: $model.soft, title: Text("소프트 캘린더", bundle: .module),
                        detail: Text(
                            "나에게만 보이고, 그룹에는 비어 있는 시간으로 보여요. 헬스장, 혼자 하는 공부처럼 약속이 생기면 미룰 수 있는 것에 써요.",
                            bundle: .module)
                    )
                    .accessibilityIdentifier("calendarEdit.soft")
                    Rectangle().fill(GeunalColor.fill).frame(height: 1)
                    Menu {
                        Picker(selection: $model.defaultAlert) {
                            ForEach(AlertText.choices(allDay: false), id: \.self) { rule in
                                Text(verbatim: Self.alertText(rule)).tag(rule)
                            }
                        } label: {
                            Text("기본 알림", bundle: .module)
                        }
                    } label: {
                        SettingsRow(
                            Text("기본 알림", bundle: .module), value: Text(verbatim: Self.alertText(model.defaultAlert)))
                    }
                    .accessibilityIdentifier("calendarEdit.alert")
                }
                if model.failed {
                    NoticeCard(
                        systemImage: "exclamationmark.circle",
                        text: Text("저장하지 못했어요. 다시 시도해 주세요", bundle: .module), tone: .problem)
                }
                Button {
                    Task {
                        if await model.save() != nil {
                            onDone()
                        }
                    }
                } label: {
                    if model.trimmedName.isEmpty {
                        Text("이름을 적어 주세요", bundle: .module)
                    } else {
                        Text("저장", bundle: .module)
                    }
                }
                .buttonStyle(.geunal(.primary))
                .disabled(!model.canSave)
                .accessibilityIdentifier("calendarEdit.save")
                if !model.isNew {
                    deleteButton
                }
            }
            .padding(.horizontal, Metrics.Sheet.Padding.side)
            .padding(.top, Space.l)
            .padding(.bottom, Space.l)
        }
        .scrollDismissesKeyboard(.interactively)
        .confirmationDialog(deleteQuestion, isPresented: $confirmsDelete, titleVisibility: .visible) {
            Button(role: .destructive) {
                Task {
                    if await model.delete() {
                        onDone()
                    }
                }
            } label: {
                Text("삭제", bundle: .module)
            }
        } message: {
            Text("되돌릴 수 없어요.", bundle: .module)
        }
        .sheetChrome(height: nil)
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Group {
                    if model.isNew {
                        Text("새 캘린더", bundle: .module)
                    } else {
                        Text(verbatim: model.name)
                    }
                }
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .accessibilityAddTraits(.isHeader)
                Text(verbatim: subtitle)
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

    private var nameField: some View {
        HStack(spacing: Space.s) {
            Text("이름", bundle: .module)
                .foregroundStyle(GeunalColor.secondaryText)
            TextField(text: $model.name) {
                Text("학교", bundle: .module)
            }
            .foregroundStyle(GeunalColor.ink)
            .submitLabel(.done)
            .accessibilityIdentifier("calendarEdit.name")
        }
        .font(.system(size: 17))
        .padding(.horizontal, Space.m)
        .frame(height: 52)
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.input))
    }

    @ViewBuilder private var deleteButton: some View {
        VStack(spacing: 4) {
            Button {
                confirmsDelete = true
            } label: {
                if model.eventCount > 0 {
                    Text("캘린더 삭제 · 일정 \(Int(model.eventCount))개도 지워져요", bundle: .module)
                } else {
                    Text("캘린더 삭제", bundle: .module)
                }
            }
            .buttonStyle(.geunal(.destructive))
            .disabled(!model.canDelete)
            .accessibilityIdentifier("calendarEdit.delete")
            if model.isLast {
                Text("캘린더는 하나 이상 있어야 해요", bundle: .module)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.tertiaryText)
            }
        }
        .frame(maxWidth: .infinity)
    }

    /// "소프트 캘린더 · 올리브" or just the color's name.
    private var subtitle: String {
        let color = model.color.groupColor.name
        return model.soft ? String(localized: "소프트 캘린더 · \(color)", bundle: .module) : color
    }

    /// "학교를 지울까요?"
    private var deleteQuestion: Text {
        let name = model.trimmedName
        let particle = Josa.object(after: name)
        return Text("\(name)\(particle) 지울까요?", bundle: .module)
    }

    /// A calendar's own default can follow the app-wide one (알림 설정, screen 19).
    static func alertText(_ rule: AlertRule) -> String {
        rule == .inherit ? String(localized: "알림 설정을 따름", bundle: .module) : AlertText.describe(rule, allDay: false)
    }
}

/// One of the twelve colors: tint 5 in a rounded square, the chosen one with a white and an ink ring and a check.
private struct ColorSwatch: View {
    let color: Palette
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            RoundedRectangle(cornerRadius: Radius.input)
                .fill(color.groupColor.representative)
                .aspectRatio(1.15, contentMode: .fit)
                .overlay {
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(.white)
                    }
                }
                .padding(selected ? 3 : 0)
                .overlay {
                    if selected {
                        RoundedRectangle(cornerRadius: Radius.input + 3)
                            .strokeBorder(GeunalColor.ink, lineWidth: 2)
                    }
                }
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(Text(verbatim: color.groupColor.name))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
