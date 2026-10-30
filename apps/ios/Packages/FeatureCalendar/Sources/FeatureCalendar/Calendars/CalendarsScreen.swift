import DesignSystem
import GeunalCore
import SwiftUI

/// 캘린더 관리 (m1.md screen 15, boards/Calendars): what the colors are for, then "내 캘린더 · N개" with a swatch, the
/// name, what the calendar holds and the show switch, and "＋ 새 캘린더". A row opens screen 16.
public struct CalendarsScreen: View {
    private enum Sheet: Identifiable {
        case new
        case edit(CalendarView)

        var id: String {
            switch self {
            case .new: "new"
            case .edit(let calendar): "edit.\(calendar.id)"
            }
        }
    }

    @State private var model: CalendarsModel
    @State private var sheet: Sheet?
    private let core: any CoreProtocol

    public init(core: any CoreProtocol) {
        _model = State(initialValue: CalendarsModel(core: core))
        self.core = core
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.s) {
                Text("캘린더 관리", bundle: .module)
                    .typeStyle(.largeTitle)
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("색은 내 화면의 일정 색이에요. 그룹에는 어느 캘린더든 비어 있는지만 보여요.", bundle: .module)
                    .typeStyle(.secondary)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                if model.failed {
                    NoticeCard(
                        systemImage: "exclamationmark.circle",
                        text: Text("바꾸지 못했어요. 다시 시도해 주세요", bundle: .module), tone: .problem)
                }
                RowGroup(header: Text("내 캘린더 · \(model.own.count)개", bundle: .module)) {
                    ForEach(model.own, id: \.id) { calendar in
                        CalendarRow(
                            calendar: calendar,
                            visible: Binding(
                                get: { calendar.visible },
                                set: { visible in Task { await model.setVisible(calendar.id, visible) } }),
                            onOpen: { sheet = .edit(calendar) })
                    }
                    Button {
                        sheet = .new
                    } label: {
                        Label {
                            Text("새 캘린더", bundle: .module)
                        } icon: {
                            Image(systemName: "plus")
                        }
                        .typeStyle(.body)
                        .foregroundStyle(GeunalColor.ink)
                        .padding(.horizontal, Space.m)
                        .frame(maxWidth: .infinity, minHeight: Metrics.Row.setting, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("calendars.new")
                }
                .padding(.top, Space.s)
            }
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.xxl)
        }
        .background(GeunalColor.groupedBackground)
        .navigationTitle(Text("캘린더 관리", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
            ToolbarItem(placement: .topBarTrailing) {
                IconButton(systemImage: "plus", label: Text("새 캘린더", bundle: .module)) {
                    sheet = .new
                }
                .accessibilityIdentifier("calendars.add")
            }
        }
        .task { await model.load() }
        .sheet(item: $sheet, onDismiss: { Task { await model.load() } }) { sheet in
            switch sheet {
            case .new:
                CalendarEditSheet(model: CalendarEditModel(core: core)) { self.sheet = nil }
            case .edit(let calendar):
                CalendarEditSheet(
                    model: CalendarEditModel(calendar: calendar, isLast: model.own.count <= 1, core: core)
                ) { self.sheet = nil }
            }
        }
    }
}

/// 학교 · 바다 · 일정 42개 · the show switch.
private struct CalendarRow: View {
    let calendar: CalendarView
    @Binding var visible: Bool
    let onOpen: () -> Void

    var body: some View {
        HStack(spacing: Space.s) {
            Button(action: onOpen) {
                HStack(spacing: Space.s) {
                    EventFill(calendar.color, soft: calendar.soft)
                        .frame(width: 14, height: 14)
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: calendar.name)
                            .typeStyle(.body)
                            .foregroundStyle(GeunalColor.ink)
                        Text(verbatim: CalendarsModel.detail(calendar))
                            .typeStyle(.caption)
                            .foregroundStyle(GeunalColor.secondaryText)
                    }
                    Spacer(minLength: Space.xs)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint(Text("눌러서 고쳐요", bundle: .module))
            .accessibilityIdentifier("calendars.row.\(calendar.name)")
            Toggle(isOn: $visible) {
                Text("\(calendar.name) 보이기", bundle: .module)
            }
            .labelsHidden()
            .tint(GeunalColor.ink)
            .accessibilityIdentifier("calendars.visible.\(calendar.name)")
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: Metrics.Row.twoLine)
        .overlay(alignment: .bottom) {
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
        }
    }
}
