import DesignSystem
import GeunalCore
import SwiftUI

/// 캘린더 관리 (m1.md screen 15, boards/Calendars): what the colors are for, then "내 캘린더 · N개" with a swatch, the
/// name, what the calendar holds and the show switch, and "＋ 새 캘린더". A row opens screen 16. Below come the phone's
/// calendars, with the import switch and a checkbox for each.
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
    private let mirror: DeviceCalendarMirror?

    public init(core: any CoreProtocol, mirror: DeviceCalendarMirror? = nil) {
        _model = State(initialValue: CalendarsModel(core: core))
        self.core = core
        self.mirror = mirror
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
                if let mirror {
                    DeviceCalendarsGroup(mirror: mirror)
                        .padding(.top, Space.s)
                }
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

/// 기기 캘린더 (m1.md screen 15): the import switch, a checkbox for each of the phone's calendars, and what importing
/// means. Access refused in Settings turns the switch into "설정에서 켜기"; while the switch is off, the list dims.
private struct DeviceCalendarsGroup: View {
    let mirror: DeviceCalendarMirror

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            RowGroup(header: Text("기기 캘린더", bundle: .module)) {
                HStack(spacing: Space.s) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("기기 캘린더 가져오기", bundle: .module)
                            .typeStyle(.body)
                            .foregroundStyle(GeunalColor.ink)
                        Text("읽기만 해요. 제목은 이 기기 밖으로 나가지 않아요.", bundle: .module)
                            .typeStyle(.caption)
                            .foregroundStyle(GeunalColor.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: Space.xs)
                    if mirror.access == .denied {
                        Button {
                            mirror.openSettings()
                        } label: {
                            Text("설정에서 켜기", bundle: .module)
                                .typeStyle(.secondary)
                                .foregroundStyle(GeunalColor.ink)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("calendars.device.settings")
                    } else {
                        Toggle(
                            isOn: Binding(
                                get: { mirror.isOn }, set: { on in Task { await mirror.setOn(on) } })
                        ) {
                            Text("기기 캘린더 가져오기", bundle: .module)
                        }
                        .labelsHidden()
                        .tint(GeunalColor.ink)
                        .disabled(mirror.isWorking)
                        .accessibilityIdentifier("calendars.device.import")
                    }
                }
                .padding(.horizontal, Space.m)
                .frame(minHeight: Metrics.Row.twoLine)
                if mirror.loaded && mirror.access == .granted && mirror.calendars.isEmpty {
                    Text("기기에 캘린더가 없어요", bundle: .module)
                        .typeStyle(.secondary)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .padding(.horizontal, Space.m)
                        .frame(maxWidth: .infinity, minHeight: Metrics.Row.setting, alignment: .leading)
                        .overlay(alignment: .top) {
                            Rectangle().fill(GeunalColor.fill).frame(height: 1)
                        }
                }
                ForEach(mirror.calendars) { calendar in
                    DeviceCalendarRow(calendar: calendar, ticked: mirror.selected.contains(calendar.id)) {
                        Task { await mirror.toggle(calendar.id) }
                    }
                }
                .opacity(mirror.isOn ? 1 : 0.4)
                .disabled(!mirror.isOn || mirror.isWorking)
            }
            if mirror.failed {
                NoticeCard(
                    systemImage: "exclamationmark.circle",
                    text: Text("기기 캘린더를 가져오지 못했어요. 다시 시도해 주세요", bundle: .module), tone: .problem)
            }
            Text(
                "가져온 일정은 내 캘린더에 ‘기기 캘린더’ 색으로 보이고, 그룹에는 비어 있는지만 보여요. 이 앱에서 고치거나 지울 수 없어요.",
                bundle: .module
            )
            .typeStyle(.caption)
            .foregroundStyle(GeunalColor.secondaryText)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, Space.xs)
        }
        .task { await mirror.load() }
    }
}

/// ☑ iCloud · 개인 · 일정 9개.
private struct DeviceCalendarRow: View {
    let calendar: DeviceCalendarSummary
    let ticked: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: Space.s) {
                Image(systemName: ticked ? "checkmark.square.fill" : "square")
                    .foregroundStyle(ticked ? GeunalColor.ink : GeunalColor.tertiaryText)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: calendar.name)
                        .typeStyle(.body)
                        .foregroundStyle(GeunalColor.ink)
                    Text("일정 \(calendar.eventCount)개", bundle: .module)
                        .typeStyle(.caption)
                        .foregroundStyle(GeunalColor.secondaryText)
                }
                Spacer(minLength: Space.xs)
            }
            .padding(.horizontal, Space.m)
            .frame(minHeight: Metrics.Row.twoLine)
            .contentShape(Rectangle())
            .overlay(alignment: .top) {
                Rectangle().fill(GeunalColor.fill).frame(height: 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(ticked ? .isSelected : [])
        .accessibilityIdentifier("calendars.device.\(calendar.name)")
    }
}
