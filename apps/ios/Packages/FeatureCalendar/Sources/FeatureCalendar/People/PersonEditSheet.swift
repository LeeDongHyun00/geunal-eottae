import DesignSystem
import GeunalCore
import SwiftUI

/// The person sheet (m1.md screen 17, boards/PersonEdit): the name, 양력·음력, the birthday and the year on wheels that
/// open in place, the lunar note, the alert and the suggestion switch, "연락처에서 가져오기" for a new person, "저장",
/// and the red "이 사람 지우기" for one that exists.
struct PersonEditSheet: View {
    private enum Wheel {
        case birthday
        case year
    }

    @State private var model: PersonEditModel
    @State private var wheel: Wheel?
    @State private var confirmsDelete = false
    private let onDone: () -> Void
    private let onImportContacts: (() -> Void)?
    @Environment(\.dismiss) private var dismiss

    init(model: PersonEditModel, onDone: @escaping () -> Void, onImportContacts: (() -> Void)? = nil) {
        _model = State(initialValue: model)
        self.onDone = onDone
        self.onImportContacts = onImportContacts
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                header
                if model.readOnly {
                    NoticeCard(
                        systemImage: "person.2",
                        text: Text("그룹에서 공개한 생일이에요. 알림만 바꿀 수 있어요.", bundle: .module), tone: .calm)
                }
                nameField
                SegmentedControl(
                    selection: Binding(get: { model.kind }, set: { model.setKind($0) }),
                    options: [
                        (value: PersonEditModel.Kind.solar, label: Text("양력", bundle: .module)),
                        (value: PersonEditModel.Kind.lunar, label: Text("음력", bundle: .module)),
                    ],
                    fillsWidth: true
                )
                .disabled(model.readOnly)
                .accessibilityIdentifier("person.calendar")
                dateRows
                if model.kind == .lunar {
                    Text(verbatim: PeopleText.lunarNote(solarThisYear: model.solarThisYear))
                        .typeStyle(.caption)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .padding(.horizontal, Space.m)
                        .accessibilityIdentifier("person.lunarNote")
                }
                alertRows
                if model.isNew, let onImportContacts {
                    Button(action: onImportContacts) {
                        Text("연락처에서 가져오기", bundle: .module)
                    }
                    .buttonStyle(.geunal(.pill))
                    .accessibilityIdentifier("person.importContacts")
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
                    if model.readOnly || !model.trimmedName.isEmpty {
                        Text("저장", bundle: .module)
                    } else {
                        Text("이름을 적어 주세요", bundle: .module)
                    }
                }
                .buttonStyle(.geunal(.primary))
                .disabled(!model.canSave)
                .accessibilityIdentifier("person.save")
                if !model.isNew && !model.readOnly {
                    Button {
                        confirmsDelete = true
                    } label: {
                        Text("이 사람 지우기", bundle: .module)
                    }
                    .buttonStyle(.geunal(.destructive))
                    .accessibilityIdentifier("person.delete")
                }
            }
            .padding(.horizontal, Metrics.Sheet.Padding.side)
            .padding(.top, Space.l)
            .padding(.bottom, Space.l)
        }
        .scrollDismissesKeyboard(.interactively)
        .task(id: model.birthday) { await model.refreshNote() }
        .confirmationDialog(deleteQuestion, isPresented: $confirmsDelete, titleVisibility: .visible) {
            Button(role: .destructive) {
                Task {
                    if await model.delete() {
                        onDone()
                    }
                }
            } label: {
                Text("지우기", bundle: .module)
            }
        } message: {
            Text("캘린더와 알림에서도 빠져요.", bundle: .module)
        }
        .sheetChrome(height: nil)
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Group {
                    if model.isNew {
                        Text("사람 추가", bundle: .module)
                    } else {
                        Text(verbatim: model.name)
                    }
                }
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .accessibilityAddTraits(.isHeader)
                Text("이름과 생일은 이 기기와 암호화 백업에만 있어요.", bundle: .module)
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
                Text("이름", bundle: .module)
            }
            .foregroundStyle(model.readOnly ? GeunalColor.secondaryText : GeunalColor.ink)
            .textContentType(.name)
            .submitLabel(.done)
            .disabled(model.readOnly)
            .accessibilityIdentifier("person.name")
        }
        .font(.system(size: 17))
        .padding(.horizontal, Space.m)
        .frame(height: 52)
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.input))
    }

    /// 생일 and 태어난 해; tapping one opens its wheel under it and closes the other.
    private var dateRows: some View {
        VStack(spacing: 0) {
            Button {
                toggle(.birthday)
            } label: {
                SettingsRow(Text("생일", bundle: .module), value: Text(verbatim: birthdayValue))
            }
            .buttonStyle(.plain)
            .disabled(model.readOnly)
            .accessibilityIdentifier("person.birthday")
            if wheel == .birthday {
                birthdayWheel
            }
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            Button {
                toggle(.year)
            } label: {
                HStack(spacing: Space.s) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("태어난 해", bundle: .module)
                            .typeStyle(.body)
                            .foregroundStyle(GeunalColor.ink)
                        Text("몰라도 돼요. 나이는 보여 주지 않아요.", bundle: .module)
                            .typeStyle(.caption)
                            .foregroundStyle(GeunalColor.secondaryText)
                    }
                    Spacer(minLength: Space.s)
                    Text(verbatim: yearValue)
                        .typeStyle(.body)
                        .monospacedDigit()
                        .foregroundStyle(GeunalColor.secondaryText)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(GeunalColor.tertiaryText)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, Space.m)
                .frame(minHeight: Metrics.Row.twoLine)
                .contentShape(Rectangle())
                .accessibilityElement(children: .combine)
            }
            .buttonStyle(.plain)
            .disabled(model.readOnly)
            .accessibilityIdentifier("person.year")
            if wheel == .year {
                Picker(selection: $model.year) {
                    Text("선택 안 함", bundle: .module).tag(Int?.none)
                    ForEach(model.years, id: \.self) { year in
                        Text(verbatim: PeopleText.year(year)).tag(Int?.some(year))
                    }
                } label: {
                    Text("태어난 해", bundle: .module)
                }
                .pickerStyle(.wheel)
                .frame(height: 160)
                .accessibilityIdentifier("person.yearWheel")
            }
        }
    }

    private var birthdayWheel: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Picker(selection: Binding(get: { model.month ?? 1 }, set: { model.setMonth($0) })) {
                    ForEach(1...12, id: \.self) { month in
                        Text("\(month)월", bundle: .module).tag(month)
                    }
                } label: {
                    Text("월", bundle: .module)
                }
                .pickerStyle(.wheel)
                .accessibilityIdentifier("person.monthWheel")
                Picker(selection: Binding(get: { model.day ?? 1 }, set: { model.setDay($0) })) {
                    ForEach(model.days, id: \.self) { day in
                        Text("\(day)일", bundle: .module).tag(day)
                    }
                } label: {
                    Text("일", bundle: .module)
                }
                .pickerStyle(.wheel)
                .accessibilityIdentifier("person.dayWheel")
            }
            .frame(height: 160)
            if model.kind == .lunar {
                GeunalToggle(
                    isOn: $model.leapMonth, title: Text("윤달", bundle: .module),
                    detail: Text("그해에 윤달이 없으면 같은 숫자의 달에 넣어요.", bundle: .module))
            }
        }
    }

    private var alertRows: some View {
        VStack(spacing: 0) {
            Menu {
                Picker(selection: $model.alert) {
                    ForEach(BirthdayAlertText.choices, id: \.self) { rule in
                        Text(verbatim: BirthdayAlertText.choice(rule)).tag(rule)
                    }
                } label: {
                    Text("알림", bundle: .module)
                }
            } label: {
                SettingsRow(
                    Text("알림", bundle: .module), value: Text(verbatim: BirthdayAlertText.describe(model.alert)))
            }
            .accessibilityIdentifier("person.alert")
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
            GeunalToggle(
                isOn: $model.suggest, title: Text("날짜 잡기 추천에 쓰기", bundle: .module),
                detail: Text("생일 7일 안이면 추천 칩에 ‘\(suggestName) 생일 약속’이 떠요", bundle: .module)
            )
            .disabled(model.readOnly)
            .accessibilityIdentifier("person.suggest")
        }
    }

    /// "민수를 지울까요?"
    private var deleteQuestion: Text {
        let name = model.trimmedName
        let particle = Josa.object(after: name)
        return Text("\(name)\(particle) 지울까요?", bundle: .module)
    }

    private var birthdayValue: String {
        guard let birthday = model.birthday else { return String(localized: "선택", bundle: .module) }
        return PeopleText.birthday(birthday)
    }

    private var yearValue: String {
        guard let year = model.year else { return String(localized: "선택", bundle: .module) }
        return PeopleText.year(year)
    }

    /// The chip names the person once there is a name: "‘엄마 생일 약속’".
    private var suggestName: String {
        model.trimmedName.isEmpty ? "OO" : model.trimmedName
    }

    private func toggle(_ next: Wheel) {
        if next == .birthday, model.birthday == nil {
            // What the wheel shows is what gets saved, so it starts on a date.
            model.setMonth(1)
        }
        withAnimation(.easeOut(duration: 0.2)) {
            wheel = wheel == next ? nil : next
        }
    }
}
