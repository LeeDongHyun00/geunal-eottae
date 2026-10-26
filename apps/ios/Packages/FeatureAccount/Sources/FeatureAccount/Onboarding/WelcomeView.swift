import DesignSystem
import SwiftUI

/// The welcome screen (m1.md screen 1, boards/Welcome.dc.html): the app name, a card with three groups' free-time
/// strips for one day, the headline, and the way in.
public struct WelcomeView: View {
    private let onStart: () -> Void
    private let onInviteCode: () -> Void

    public init(onStart: @escaping () -> Void, onInviteCode: @escaping () -> Void) {
        self.onStart = onStart
        self.onInviteCode = onInviteCode
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("그날어때", bundle: .module)
                        .font(.system(size: 20, weight: .bold))
                        .tracking(-0.6)
                        .foregroundStyle(GeunalColor.ink)
                        .padding(.top, 70 - 54)
                        .accessibilityAddTraits(.isHeader)
                    PreviewStripsCard()
                        .padding(.top, Space.xl)
                    VStack(alignment: .leading, spacing: 14) {
                        Text("일정은 쉽게,\n약속은 빠르게", bundle: .module)
                            .font(.system(size: 36, weight: .semibold))
                            .tracking(-1.26)
                            .lineSpacing(2)
                            .foregroundStyle(GeunalColor.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("멤버들의 일정을 합쳐 비어 있는 시간을 보여 주고, 날짜를 잡으면 수락한 사람의 캘린더에 바로 들어가요.", bundle: .module)
                            .typeStyle(.body)
                            .lineSpacing(5)
                            .foregroundStyle(GeunalColor.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                        Label {
                            Text("일정과 대화는 멤버의 기기에서만 열려요", bundle: .module)
                        } icon: {
                            Image(systemName: "lock")
                        }
                        .font(.system(size: 14))
                        .foregroundStyle(GeunalColor.secondaryText)
                    }
                    .padding(.top, 48)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, Space.l)
            }
            VStack(spacing: 4) {
                Button(action: onStart) {
                    Text("시작하기", bundle: .module)
                }
                .buttonStyle(.geunal(.primary, height: 56))
                .accessibilityIdentifier("welcome.start")
                Button(action: onInviteCode) {
                    Text("초대 코드가 있어요", bundle: .module)
                }
                .buttonStyle(.geunal(.text, height: 48))
                .accessibilityIdentifier("welcome.invite")
            }
            .padding(.horizontal, 24)
            .padding(.bottom, Space.s)
        }
        .background(GeunalColor.background)
        .toolbar(.hidden, for: .navigationBar)
    }
}

/// The welcome card's example day. The levels are the board's: one hour per digit, 0 is the empty cell and 5 is
/// everyone free, in each group's own color (docs/design/README.md §2.2).
private struct PreviewStripsCard: View {
    private struct Row: Identifiable {
        let id: Int
        let name: Text
        let color: GroupColor
        let levels: String
    }

    private let rows: [Row] = [
        Row(id: 0, name: Text("주말모임", bundle: .module), color: .grape, levels: "555555554333334345555444"),
        Row(id: 1, name: Text("가족", bundle: .module), color: .blossom, levels: "555555555544332233444555"),
        Row(id: 2, name: Text("카페 알바", bundle: .module), color: .mint, levels: "555555432112223321123455"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            HStack(alignment: .firstTextBaseline) {
                Text("11월 7일 토요일", bundle: .module)
                    .typeStyle(.bodyStrong)
                    .foregroundStyle(GeunalColor.saturday)
                Spacer()
                Text("0시 — 24시", bundle: .module)
                    .typeStyle(.caption)
                    .monospacedDigit()
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            ForEach(rows) { row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(row.color.representative)
                            .frame(width: Metrics.Chip.dot, height: Metrics.Chip.dot)
                        row.name
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(GeunalColor.ink)
                    }
                    HourStrip(color: row.color, levels: row.levels)
                        .frame(height: 22)
                }
            }
        }
        .padding(Space.l)
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: 24))
        // A picture of the idea, not data: VoiceOver reads the headline below instead.
        .accessibilityHidden(true)
    }
}

/// 24 hour cells in one group's tints with a 1 pt tick every six hours (components.md §8, "비어 있음 띠").
private struct HourStrip: View {
    let color: GroupColor
    let levels: String

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width / 24
            HStack(spacing: 0) {
                ForEach(Array(levels.enumerated()), id: \.offset) { index, character in
                    let level = Int(String(character)) ?? 0
                    Rectangle()
                        .fill(level == 0 ? GeunalColor.background : color.tint(level))
                        .frame(width: width)
                        .overlay(alignment: .leading) {
                            if index > 0 && index % 6 == 0 {
                                Rectangle()
                                    .fill(GeunalColor.groupedBackground)
                                    .frame(width: Metrics.Grid.stripTick)
                            }
                        }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
