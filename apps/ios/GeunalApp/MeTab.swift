import DesignSystem
import GeunalCore
import SwiftUI

/// The 나 tab's account card. The rest of the screen (m1.md screen 12: calendars, recovery code, backup, deletion)
/// follows with those screens.
struct MeTab: View {
    let core: any CoreProtocol
    @State private var account: AccountView?
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.l) {
                    Text("나")
                        .typeStyle(.largeTitle)
                        .foregroundStyle(GeunalColor.ink)
                        .padding(.top, Space.xl)
                        .accessibilityAddTraits(.isHeader)
                    profile
                }
                .padding(.horizontal, Space.m)
            }
            .background(GeunalColor.groupedBackground)
            .task {
                let core = self.core
                account = await Task.detached { (try? core.account()) ?? nil }.value
                loaded = true
            }
        }
    }

    private var profile: some View {
        HStack(spacing: Space.m) {
            Text(verbatim: String((account?.displayName ?? "").prefix(1)))
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .frame(width: 48, height: 48)
                .background(GroupColor.grape.tint(2), in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                if let account {
                    Text(verbatim: account.displayName)
                        .typeStyle(.bodyStrong)
                        .foregroundStyle(GeunalColor.ink)
                    kind(of: account)
                        .typeStyle(.caption)
                        .foregroundStyle(GeunalColor.secondaryText)
                } else if loaded {
                    Text("계정 없이 쓰는 중이에요")
                        .typeStyle(.bodyStrong)
                        .foregroundStyle(GeunalColor.ink)
                    Text("이 기기에서만 일정을 쓰고 있어요")
                        .typeStyle(.caption)
                        .foregroundStyle(GeunalColor.secondaryText)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(Space.m)
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("me.profile")
    }

    private func kind(of account: AccountView) -> Text {
        if account.providers.contains(.kakao) {
            return Text("카카오 계정")
        }
        if account.providers.contains(.apple) {
            return Text("Apple 계정")
        }
        return Text("게스트 · 기기를 잃으면 복구 코드로만 돌아올 수 있어요")
    }
}
