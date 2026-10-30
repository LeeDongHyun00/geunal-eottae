import DesignSystem
import GeunalCore
import SwiftUI

/// 기기 로그아웃됨 (m1.md screen 23, boards/Revoked): another device signed in to this account, so this one is signed
/// out. The core has already wiped the session, the device key, the code's entropy and the group state, and kept the
/// calendar. The note differs by how the other device came in (m1.md decision 15).
public struct RevokedSheet: View {
    private let revocation: Revocation
    private let when: String
    private let onSignIn: () -> Void
    private let onCalendarOnly: () -> Void

    /// `when` is the sign-in's time in the app's date style ("10월 25일 18:20").
    public init(
        revocation: Revocation, when: String, onSignIn: @escaping () -> Void, onCalendarOnly: @escaping () -> Void
    ) {
        self.revocation = revocation
        self.when = when
        self.onSignIn = onSignIn
        self.onCalendarOnly = onCalendarOnly
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                Text("다른 기기에서 로그인했어요", bundle: .module)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                explanation
                    .typeStyle(.body)
                    .foregroundStyle(GeunalColor.ink)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(alignment: .top, spacing: Space.s) {
                    list(
                        Text("이 기기에 남는 것", bundle: .module),
                        [
                            Text("내 일정과 캘린더", bundle: .module), Text("사람·생일", bundle: .module),
                            Text("교대 템플릿", bundle: .module), Text("일정 알림", bundle: .module),
                        ])
                    list(
                        Text("이 기기에서 지워지는 것", bundle: .module),
                        [
                            Text("그룹과 채팅", bundle: .module), Text("백업 열쇠와 복구 코드", bundle: .module),
                            Text("날짜 잡기 알림", bundle: .module),
                        ])
                }
                note
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                VStack(spacing: Space.xs) {
                    Button(action: onSignIn) {
                        Text("이 기기에서 다시 로그인", bundle: .module)
                    }
                    .buttonStyle(.geunal(.primary))
                    .accessibilityIdentifier("revoked.signIn")
                    Button(action: onCalendarOnly) {
                        Text("캘린더만 쓸게요", bundle: .module)
                    }
                    .buttonStyle(.geunal(.text))
                    .accessibilityIdentifier("revoked.calendarOnly")
                }
            }
            .padding(.horizontal, Metrics.Sheet.Padding.side)
            .padding(.top, Space.l)
            .padding(.bottom, Space.s)
        }
        .sheetChrome(height: nil, dismissible: false)
    }

    private var platform: String {
        revocation.byPlatform == "android"
            ? String(localized: "안드로이드 기기", bundle: .module) : String(localized: "iPhone", bundle: .module)
    }

    private var explanation: Text {
        switch revocation.via {
        case .provider:
            Text("\(when)에 \(platform)에서 카카오·Apple 계정으로 로그인해서 이 기기는 로그아웃됐어요. 계정은 한 기기에서만 쓸 수 있어요.", bundle: .module)
        case .recovery:
            Text("\(when)에 \(platform)에서 복구 코드로 이 계정에 들어와서 이 기기는 로그아웃됐어요. 계정은 한 기기에서만 쓸 수 있어요.", bundle: .module)
        }
    }

    private var note: Text {
        switch revocation.via {
        case .provider:
            Text(
                "캘린더는 그대로 쓸 수 있어요. 내가 한 로그인이 아니라면 카카오·Apple 계정이 다른 사람에게 알려졌을 수 있어요. 그 계정의 비밀번호를 바꿔 주세요.",
                bundle: .module)
        case .recovery:
            Text(
                "캘린더는 그대로 쓸 수 있어요. 내가 한 게 아니라면 복구 코드를 적은 종이를 누가 봤을 수 있어요. 코드를 아는 사람은 누구나 이 계정으로 들어올 수 있으니, 다시 로그인한 뒤 카카오·Apple 계정을 연결해 두세요.",
                bundle: .module)
        }
    }

    private func list(_ title: Text, _ items: [Text]) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            title
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(GeunalColor.secondaryText)
            ForEach(items.indices, id: \.self) { index in
                items[index]
                    .typeStyle(.body)
                    .foregroundStyle(GeunalColor.ink)
            }
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .combine)
    }
}
