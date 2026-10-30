import DesignSystem
import GeunalCore
import SwiftUI

/// 사람·생일 (m1.md screen 11, boards/People): the name search, the birthdays of the next 30 days with their D-day and
/// where they came from, everyone by name, and the contacts import with its note. A person opens screen 17.
public struct PeopleScreen: View {
    private enum Sheet: Identifiable {
        case add
        case edit(Person)
        case importContacts

        var id: String {
            switch self {
            case .add: "add"
            case .edit(let person): "edit.\(person.id)"
            case .importContacts: "import"
            }
        }
    }

    @State private var model: PeopleModel
    @State private var sheet: Sheet?
    @State private var focused = false
    private let core: any CoreProtocol
    private let contacts: any ContactBook
    private let toasts: ToastCenter
    private let focus: String?

    /// `focus` opens that person's sheet once the list is in: a birthday on the next-up bar or in an alert leads to
    /// the person (m1.md §2.6).
    public init(core: any CoreProtocol, contacts: any ContactBook, toasts: ToastCenter, focus: String? = nil) {
        _model = State(initialValue: PeopleModel(core: core))
        self.core = core
        self.contacts = contacts
        self.toasts = toasts
        self.focus = focus
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("사람·생일", bundle: .module)
                    .typeStyle(.largeTitle)
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                if model.isEmpty {
                    empty
                        .padding(.top, Space.xxl)
                } else {
                    searchField
                        .padding(.top, Space.m)
                    if !model.matchingUpcoming.isEmpty {
                        header(Text("다가오는 생일", bundle: .module))
                        ForEach(model.matchingUpcoming, id: \.id) { person in
                            Button {
                                sheet = .edit(person)
                            } label: {
                                UpcomingRow(person: person, today: model.today)
                            }
                            .buttonStyle(.pressable)
                            .accessibilityIdentifier("people.upcoming.\(person.name)")
                        }
                    }
                    header(Text("모든 사람 · \(model.people.count)명", bundle: .module))
                    ForEach(model.matchingPeople, id: \.id) { person in
                        Button {
                            sheet = .edit(person)
                        } label: {
                            PersonRow(person: person)
                        }
                        .buttonStyle(.pressable)
                        .accessibilityIdentifier("people.person.\(person.name)")
                    }
                    if model.matchingPeople.isEmpty {
                        Text("찾는 이름이 없어요", bundle: .module)
                            .typeStyle(.secondary)
                            .foregroundStyle(GeunalColor.secondaryText)
                            .padding(.vertical, Space.m)
                    }
                    importButton
                        .padding(.top, Space.l)
                }
            }
            .padding(.horizontal, Space.l)
            .padding(.bottom, Space.xxl)
        }
        .scrollDismissesKeyboard(.immediately)
        .background(GeunalColor.background)
        .navigationTitle(Text("사람·생일", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
            ToolbarItem(placement: .topBarTrailing) {
                IconButton(systemImage: "plus", label: Text("사람 추가", bundle: .module)) {
                    sheet = .add
                }
                .accessibilityIdentifier("people.add")
            }
        }
        .task {
            await model.load()
            if !focused, let focus, let person = model.person(focus) {
                focused = true
                sheet = .edit(person)
            }
        }
        .sheet(item: $sheet, onDismiss: { Task { await model.load() } }) { sheet in
            switch sheet {
            case .add:
                PersonEditSheet(
                    model: PersonEditModel(core: core), onDone: { self.sheet = nil },
                    onImportContacts: { self.sheet = .importContacts })
            case .edit(let person):
                PersonEditSheet(model: PersonEditModel(person: person, core: core), onDone: { self.sheet = nil })
            case .importContacts:
                ContactImportSheet(
                    model: ContactImportModel(core: core, contacts: contacts),
                    onFinished: { result in
                        toasts.show(ContactImportModel.summary(result))
                        self.sheet = nil
                    },
                    onAddByHand: { self.sheet = .add })
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: Space.xs) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 17))
                .foregroundStyle(GeunalColor.secondaryText)
                .accessibilityHidden(true)
            TextField(text: $model.query) {
                Text("이름 검색", bundle: .module)
            }
            .typeStyle(.body)
            .foregroundStyle(GeunalColor.ink)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.search)
            .accessibilityIdentifier("people.search")
        }
        .padding(.horizontal, Space.m)
        .frame(height: 44)
        .background(GeunalColor.groupedBackground, in: Capsule())
    }

    private func header(_ title: Text) -> some View {
        title
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(GeunalColor.secondaryText)
            .padding(.top, Space.l)
            .padding(.bottom, Space.xxs)
            .accessibilityAddTraits(.isHeader)
    }

    private var importButton: some View {
        VStack(spacing: Space.s) {
            Button {
                sheet = .importContacts
            } label: {
                Label {
                    Text("연락처에서 생일 가져오기", bundle: .module)
                } icon: {
                    Image(systemName: "person.crop.square")
                }
            }
            .buttonStyle(.geunal(.secondary))
            .accessibilityIdentifier("people.import")
            Text("선택 기능이에요. 가져온 이름과 생일은 이 기기와 암호화 백업에만 있어요.", bundle: .module)
                .typeStyle(.caption)
                .foregroundStyle(GeunalColor.secondaryText)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
    }

    /// No one yet: what the list is for, and the two ways in.
    private var empty: some View {
        VStack(spacing: Space.s) {
            Image(systemName: "gift")
                .font(.system(size: 34))
                .foregroundStyle(GeunalColor.tertiaryText)
                .accessibilityHidden(true)
            Text("아직 아무도 없어요", bundle: .module)
                .typeStyle(.title2)
                .foregroundStyle(GeunalColor.ink)
            Text("생일을 넣어 두면 캘린더와 다음 할 일에 보이고, 날짜 잡기 추천에도 써요.", bundle: .module)
                .typeStyle(.secondary)
                .foregroundStyle(GeunalColor.secondaryText)
                .multilineTextAlignment(.center)
            VStack(spacing: Space.xs) {
                Button {
                    sheet = .add
                } label: {
                    Text("직접 추가", bundle: .module)
                }
                .buttonStyle(.geunal(.primary))
                .accessibilityIdentifier("people.addFirst")
                Button {
                    sheet = .importContacts
                } label: {
                    Text("연락처에서 가져오기", bundle: .module)
                }
                .buttonStyle(.geunal(.secondary))
                .accessibilityIdentifier("people.importFirst")
            }
            .padding(.top, Space.m)
            Text("선택 기능이에요. 가져온 이름과 생일은 이 기기와 암호화 백업에만 있어요.", bundle: .module)
                .typeStyle(.caption)
                .foregroundStyle(GeunalColor.secondaryText)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }
}

/// The round letter in front of a name.
struct PersonAvatar: View {
    let name: String
    let size: CGFloat

    var body: some View {
        Text(verbatim: PeopleText.initial(name))
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(GeunalColor.ink)
            .frame(width: size, height: size)
            .background(GeunalColor.fill, in: Circle())
            .accessibilityHidden(true)
    }
}

/// 민수 · 10월 30일 금요일 · D-5 · 직접 등록.
private struct UpcomingRow: View {
    let person: Person
    let today: String

    var body: some View {
        HStack(spacing: Space.s) {
            PersonAvatar(name: person.name, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: person.name)
                    .typeStyle(.bodyStrong)
                    .foregroundStyle(GeunalColor.ink)
                Text(verbatim: PeopleText.next(person, today: today))
                    .typeStyle(.secondary)
                    .monospacedDigit()
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            Spacer(minLength: Space.xs)
            VStack(alignment: .trailing, spacing: 4) {
                if let days = person.daysUntil {
                    Text(verbatim: PeopleText.dDay(days))
                        .font(.system(size: 15, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(GeunalColor.badgeText)
                        .padding(.horizontal, Space.s)
                        .frame(height: 28)
                        .background(GeunalColor.badgeFill, in: Capsule())
                }
                Text(verbatim: PeopleText.source(person.source))
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
            }
        }
        .padding(.vertical, Space.s)
        .overlay(alignment: .bottom) {
            Rectangle().fill(GeunalColor.separator).frame(height: 1)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// 가영 · 3월 14일.
private struct PersonRow: View {
    let person: Person

    var body: some View {
        HStack(spacing: Space.s) {
            PersonAvatar(name: person.name, size: 32)
            Text(verbatim: person.name)
                .typeStyle(.body)
                .foregroundStyle(GeunalColor.ink)
            Spacer(minLength: Space.xs)
            if let birthday = person.birthday {
                Text(verbatim: PeopleText.birthday(birthday))
                    .typeStyle(.body)
                    .monospacedDigit()
                    .foregroundStyle(GeunalColor.secondaryText)
            }
        }
        .frame(minHeight: Metrics.Row.setting)
        .overlay(alignment: .bottom) {
            Rectangle().fill(GeunalColor.separator).frame(height: 1)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
