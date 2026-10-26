import DesignSystem
import SwiftUI

/// The permissions screen (m1.md screen 3, boards/Permissions.dc.html). Each card says where the permission is used
/// and its limit before the system alert appears (docs/design/tone.md "권한 요청").
public struct PermissionsView: View {
    private let model: PermissionsModel
    private let onFinish: () -> Void

    public init(model: PermissionsModel, onFinish: @escaping () -> Void) {
        self.model = model
        self.onFinish = onFinish
    }

    public var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: Space.s) {
                        Group {
                            if model.showsAlphaStats {
                                Text("세 가지만\n허락해 주세요", bundle: .module)
                            } else {
                                Text("두 가지만\n허락해 주세요", bundle: .module)
                            }
                        }
                        .font(.system(size: 30, weight: .semibold))
                        .tracking(-0.9)
                        .foregroundStyle(GeunalColor.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                        Text("모두 나중에 설정에서 바꿀 수 있어요.", bundle: .module)
                            .typeStyle(.body)
                            .foregroundStyle(GeunalColor.secondaryText)
                    }
                    .padding(.horizontal, Space.s)
                    VStack(spacing: Space.xs) {
                        PermissionCard(
                            systemImage: "calendar",
                            title: Text("기기 캘린더 가져오기", bundle: .module),
                            detail: Text("읽기만 해요. 가져온 일정의 제목은 이 기기 밖으로 나가지 않고, 그룹에는 비어 있는지만 보여요.", bundle: .module),
                            button: calendarButton,
                            done: model.calendar == .granted,
                            identifier: "permissions.calendar"
                        ) {
                            Task { await model.calendarTapped() }
                        }
                        PermissionCard(
                            systemImage: "bell",
                            title: Text("알림 받기", bundle: .module),
                            detail: Text("일정 알림과 친구의 날짜 잡기·투표를 알려 드려요. 그룹마다 따로 끌 수 있어요.", bundle: .module),
                            button: notificationButton,
                            done: model.notifications == .granted,
                            identifier: "permissions.notifications"
                        ) {
                            Task { await model.notificationsTapped() }
                        }
                        if model.showsAlphaStats {
                            PermissionCard(
                                systemImage: "chart.bar",
                                title: Text("테스트 통계 보내기", bundle: .module),
                                badge: Text("알파 빌드만", bundle: .module),
                                detail: Text(
                                    "하루 한 번 숫자 네 개만 보내요: 앱을 연 횟수, 만든 일정 수, 그중 추천으로 만든 수, 비정상 종료 수. 제목·시간·이름은 가지 않아요.",
                                    bundle: .module),
                                button: model.alphaStats
                                    ? Text("보내요 · 나에서 끌 수 있어요", bundle: .module)
                                    : Text("보낼게요", bundle: .module),
                                done: model.alphaStats,
                                identifier: "permissions.alphaStats"
                            ) {
                                model.alphaStatsTapped()
                            }
                        }
                    }
                    .padding(.top, Space.l)
                }
                .padding(.horizontal, Space.m)
                .padding(.top, Space.l)
                .padding(.bottom, Space.l)
            }
            VStack(spacing: 4) {
                Button(action: onFinish) {
                    Text("캘린더 열기", bundle: .module)
                }
                .buttonStyle(.geunal(.primary))
                .disabled(!model.canOpenCalendar)
                .accessibilityIdentifier("permissions.open")
                Button(action: onFinish) {
                    Text("나중에 할게요", bundle: .module)
                }
                .buttonStyle(.geunal(.text, height: 40))
                .accessibilityIdentifier("permissions.later")
            }
            .padding(.horizontal, 24)
            .padding(.bottom, Space.s)
        }
        .background(GeunalColor.background)
        .navigationTitle(Text("권한", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
        .toolbarRole(.editor)
        .task { await model.refresh() }
    }

    private var calendarButton: Text {
        switch model.calendar {
        case .notAsked: Text("가져오기", bundle: .module)
        case .granted: Text("가져왔어요 · 캘린더 \(model.calendarCount)개", bundle: .module)
        case .denied: Text("설정에서 켜기", bundle: .module)
        }
    }

    private var notificationButton: Text {
        switch model.notifications {
        case .notAsked: Text("허용", bundle: .module)
        case .granted: Text("허용했어요", bundle: .module)
        case .denied: Text("설정에서 켜기", bundle: .module)
        }
    }
}

private struct PermissionCard: View {
    let systemImage: String
    let title: Text
    var badge: Text?
    let detail: Text
    let button: Text
    let done: Bool
    let identifier: String
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: systemImage)
                    .font(.system(size: 20))
                    .foregroundStyle(GeunalColor.ink)
                    .frame(width: 44, height: 44)
                    .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.input))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        title
                            .typeStyle(.bodyStrong)
                            .foregroundStyle(GeunalColor.ink)
                        if let badge {
                            badge
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(GeunalColor.secondaryText)
                        }
                    }
                    detail
                        .font(.system(size: 14))
                        .lineSpacing(4)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Button(action: action) {
                button
            }
            .buttonStyle(.geunal(done ? .donePill : .prominentPill, height: 40))
            .frame(maxWidth: .infinity, alignment: .trailing)
            .accessibilityIdentifier(identifier)
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, 14)
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}
