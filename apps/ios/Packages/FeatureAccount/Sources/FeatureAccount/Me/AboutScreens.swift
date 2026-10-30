import DesignSystem
import SwiftUI

/// 서버가 아는 것 · 보안 안내 (m1.md screen 12, kickoff §6 "서버가 보는 것" and "한계"): what reaches the server, what
/// never does, and what the design does not protect against yet, including that no paid audit was done.
public struct ServerKnowsScreen: View {
    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                Text("서버가 아는 것", bundle: .module)
                    .typeStyle(.largeTitle)
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("일정과 사람은 이 기기에서 잠근 채로만 나가요. 서버는 아래 것만 알아요.", bundle: .module)
                    .typeStyle(.secondary)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                AboutCard(
                    title: Text("서버가 아는 것", bundle: .module),
                    items: [
                        Text("카카오·Apple 계정 식별자, 닉네임, 만 14세를 확인한 시각", bundle: .module),
                        Text("복구 자료: 복구 코드 없이는 풀 수 없어요", bundle: .module),
                        Text("기기의 공개 서명키, 플랫폼, 푸시 토큰", bundle: .module),
                        Text("암호화 백업의 크기와 시각", bundle: .module),
                        Text("접속 IP와 로그인 시각: 법에 따라 3개월 보관해요", bundle: .module),
                        Text("알파 빌드에서 동의한 경우 하루 숫자 네 개", bundle: .module),
                    ])
                AboutCard(
                    title: Text("서버가 모르는 것", bundle: .module),
                    items: [
                        Text("일정의 제목, 시간, 메모", bundle: .module),
                        Text("사람과 생일", bundle: .module),
                        Text("복구 코드와 백업의 내용", bundle: .module),
                        Text("기기 캘린더의 일정", bundle: .module),
                    ])
                AboutCard(
                    title: Text("아직 막지 못하는 것", bundle: .module),
                    items: [
                        Text("유료 보안 감사는 받지 않았어요. 보안 전공자의 리뷰로 대신하고 있어요.", bundle: .module),
                        Text("복구 코드와 기기를 함께 잃으면 기록도 되찾을 수 없어요.", bundle: .module),
                    ])
            }
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.xxl)
        }
        .background(GeunalColor.groupedBackground)
        .navigationTitle(Text("서버가 아는 것", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
    }
}

/// 기기 바꾸기 (m1.md §2.5): the change happens on the new device, so this one explains the steps and leads to the
/// recovery code that the new device will ask for.
public struct DeviceChangeGuideScreen: View {
    private let onRecoveryCode: (() -> Void)?

    /// `onRecoveryCode` is `nil` when this device has no code to show.
    public init(onRecoveryCode: (() -> Void)?) {
        self.onRecoveryCode = onRecoveryCode
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                Text("기기 바꾸기", bundle: .module)
                    .typeStyle(.largeTitle)
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("기기 바꾸기는 새 기기에서 해요. 계정은 한 기기에서만 쓸 수 있어서, 새 기기에서 들어오면 이 기기는 로그아웃돼요.", bundle: .module)
                    .typeStyle(.secondary)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                AboutCard(
                    title: Text("새 기기에서", bundle: .module),
                    items: [
                        Text("1. 그날어때를 설치하고 같은 카카오·Apple 계정으로 로그인해요. 게스트는 \"복구 코드로 돌아오기\"를 골라요.", bundle: .module),
                        Text("2. 복구 코드 12단어를 넣으면 암호화 백업을 가져와요.", bundle: .module),
                        Text("3. 다 되면 이 기기는 로그아웃되고, 캘린더는 이 기기에도 남아요.", bundle: .module),
                    ])
                if let onRecoveryCode {
                    Button(action: onRecoveryCode) {
                        Text("복구 코드 보기", bundle: .module)
                    }
                    .buttonStyle(.geunal(.primary))
                    .accessibilityIdentifier("deviceChange.recovery")
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.xxl)
        }
        .background(GeunalColor.groupedBackground)
        .navigationTitle(Text("기기 바꾸기", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
    }
}

private struct AboutCard: View {
    let title: Text
    let items: [Text]

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            title
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(GeunalColor.secondaryText)
                .accessibilityAddTraits(.isHeader)
            ForEach(items.indices, id: \.self) { index in
                items[index]
                    .typeStyle(.body)
                    .foregroundStyle(GeunalColor.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}
