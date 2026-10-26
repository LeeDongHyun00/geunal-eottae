import DesignSystem
import GeunalCore
import SwiftUI

/// 알림 (m1.md screen 19, boards/NotifySettings): "일정" with the default alerts and the next-up bar, "사람·생일" with
/// birthday alerts and the holiday and leave suggestions, "그룹" marked M2, and the system's own settings. With the
/// system's alerts off a card says so on top and the switches read dim.
public struct NotificationSettingsScreen: View {
    @State private var model: NotificationSettingsModel
    @Environment(\.scenePhase) private var scenePhase
    private let openSettings: () -> Void

    public init(model: NotificationSettingsModel, openSettings: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.openSettings = openSettings
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.l) {
                VStack(alignment: .leading, spacing: Space.s) {
                    Text("알림", bundle: .module)
                        .typeStyle(.largeTitle)
                        .foregroundStyle(GeunalColor.ink)
                        .accessibilityAddTraits(.isHeader)
                    Text("모두 이 기기에서 울리는 알림이에요. 내용은 서버를 거치지 않아요.", bundle: .module)
                        .typeStyle(.secondary)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if model.systemOff {
                    Banner(
                        systemImage: "bell.slash", title: Text("알림이 꺼져 있어요", bundle: .module),
                        detail: Text("아래 설정은 저장되지만, 켜기 전에는 울리지 않아요.", bundle: .module),
                        actionLabel: Text("설정에서 켜기", bundle: .module), action: openSettings)
                }
                if model.failed {
                    NoticeCard(
                        systemImage: "exclamationmark.circle",
                        text: Text("바꾸지 못했어요. 다시 시도해 주세요", bundle: .module), tone: .problem)
                }
                VStack(alignment: .leading, spacing: Space.xs) {
                    RowGroup(header: Text("일정", bundle: .module)) {
                        alertMenu(
                            Text("기본 알림", bundle: .module), value: model.settings.eventAlert,
                            choices: NotificationSettingsModel.eventChoices, allDay: false,
                            identifier: "notifications.event"
                        ) { minutes in Task { await model.setEventAlert(minutes) } }
                        separator
                        alertMenu(
                            Text("종일 일정", bundle: .module), value: model.settings.allDayAlert,
                            choices: NotificationSettingsModel.allDayChoices, allDay: true,
                            identifier: "notifications.allDay"
                        ) { minutes in Task { await model.setAllDayAlert(minutes) } }
                        separator
                        GeunalToggle(
                            isOn: Binding(
                                get: { model.settings.nextUpBar },
                                set: { on in Task { await model.setNextUpBar(on) } }),
                            title: Text("다음 할 일 막대", bundle: .module),
                            detail: Text("시작 전까지 남은 시간을 함께 보여 줘요", bundle: .module)
                        )
                        .accessibilityIdentifier("notifications.nextUp")
                    }
                    Text("일정마다 편집 화면에서 따로 바꿀 수 있어요. 바꾼 일정은 기본값을 따르지 않아요.", bundle: .module)
                        .typeStyle(.caption)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .padding(.horizontal, Space.m)
                }
                .opacity(model.systemOff ? 0.4 : 1)
                RowGroup(header: Text("사람·생일", bundle: .module)) {
                    GeunalToggle(
                        isOn: Binding(
                            get: { model.settings.birthdayAlerts },
                            set: { on in Task { await model.setBirthdayAlerts(on) } }),
                        title: Text("생일 알림", bundle: .module), detail: Text("당일 09:00", bundle: .module)
                    )
                    .accessibilityIdentifier("notifications.birthdays")
                    separator
                    GeunalToggle(
                        isOn: Binding(
                            get: { model.settings.leaveSuggestions },
                            set: { on in Task { await model.setLeaveSuggestions(on) } }),
                        title: Text("연휴·연차 제안", bundle: .module),
                        detail: Text("공휴일 사이 평일 하루를 쉬면 4일 연휴가 될 때 알려 줘요", bundle: .module)
                    )
                    .accessibilityIdentifier("notifications.leave")
                }
                .opacity(model.systemOff ? 0.4 : 1)
                RowGroup(header: Text("그룹", bundle: .module)) {
                    HStack(alignment: .center, spacing: Space.s) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("채팅과 약속", bundle: .module)
                                .typeStyle(.body)
                                .foregroundStyle(GeunalColor.ink)
                            Text("그룹에 들어가면 그룹마다 채팅·약속 알림을 따로 끌 수 있어요. 전체 끄기는 1시간·오늘·계속 중에서 골라요.", bundle: .module)
                                .typeStyle(.caption)
                                .foregroundStyle(GeunalColor.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: Space.xs)
                        // The boards' grey pill for what comes with a later milestone.
                        Text(verbatim: "M2")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(GeunalColor.secondaryText)
                            .padding(.horizontal, Space.xs)
                            .frame(minHeight: Metrics.Chip.badge)
                            .background(GeunalColor.fill, in: Capsule())
                    }
                    .padding(.horizontal, Space.m)
                    .padding(.vertical, Space.s)
                    .accessibilityElement(children: .combine)
                }
                Button(action: openSettings) {
                    HStack(spacing: Space.s) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("시스템 알림 설정", bundle: .module)
                                .typeStyle(.body)
                                .foregroundStyle(GeunalColor.ink)
                            Text("소리, 잠금 화면 표시, 요약은 설정 앱에서", bundle: .module)
                                .typeStyle(.caption)
                                .foregroundStyle(GeunalColor.secondaryText)
                        }
                        Spacer(minLength: Space.xs)
                        Group {
                            if model.systemOff {
                                Text("꺼짐", bundle: .module)
                            } else {
                                Text("켜짐", bundle: .module)
                            }
                        }
                        .typeStyle(.body)
                        .foregroundStyle(GeunalColor.secondaryText)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(GeunalColor.tertiaryText)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, Space.m)
                    .frame(minHeight: Metrics.Row.twoLine)
                    .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
                    .contentShape(Rectangle())
                    .accessibilityElement(children: .combine)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("notifications.system")
            }
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.xxl)
        }
        .background(GeunalColor.groupedBackground)
        .navigationTitle(Text("알림", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
        .task { await model.load() }
        // Coming back from the Settings app with the alerts turned on.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.refreshPermission() }
            }
        }
    }

    private var separator: some View {
        Rectangle().fill(GeunalColor.fill).frame(height: 1)
    }

    private func alertMenu(
        _ title: Text, value: Int32?, choices: [Int32?], allDay: Bool, identifier: String,
        choose: @escaping (Int32?) -> Void
    ) -> some View {
        Menu {
            ForEach(choices, id: \.self) { minutes in
                Button {
                    choose(minutes)
                } label: {
                    if minutes == value {
                        Label {
                            Text(verbatim: NotificationSettingsModel.describe(minutes, allDay: allDay))
                        } icon: {
                            Image(systemName: "checkmark")
                        }
                    } else {
                        Text(verbatim: NotificationSettingsModel.describe(minutes, allDay: allDay))
                    }
                }
            }
        } label: {
            SettingsRow(title, value: Text(verbatim: NotificationSettingsModel.describe(value, allDay: allDay)))
        }
        .accessibilityIdentifier(identifier)
    }
}
