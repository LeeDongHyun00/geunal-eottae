import DesignSystem
import GeunalCore
import SwiftUI

/// "복구 코드가 없을 때" (m1.md screen 21): the code is the only key, so starting over loses the old backup (device
/// change, offer) or the old guest account (guest return). Starting fresh never goes at once: the sheet turns into a
/// confirmation that counts the backups it deletes (decision 24), whose red button asks the OS first (decision 26) and,
/// on the offer after 30 minutes, the provider (decision 25).
struct NoCodeSheet: View {
    @Bindable var model: RestoreModel
    let timeText: (Date) -> String
    /// The guest's "새 게스트 계정으로 시작", or the confirmation's red button.
    let onStartOver: () -> Void
    let onLater: () -> Void
    @State private var confirming = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if confirming {
                confirmation
            } else {
                explanation
            }
        }
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.top, Space.l)
        .padding(.bottom, Space.s)
        .sheetChrome(height: height)
    }

    private var height: CGFloat {
        if confirming {
            return model.screenLocked ? 472 : 504
        }
        return model.mode == .offer ? 472 : 412
    }

    // MARK: The first step

    private var explanation: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            heading(title)
            Group {
                first
                second
            }
            .font(.system(size: 15))
            .lineSpacing(4)
            .foregroundStyle(GeunalColor.ink.opacity(0.85))
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            VStack(spacing: Space.xs) {
                if model.mode == .offer {
                    Button(action: onLater) {
                        Text("나중에 넣을게요 · 캘린더부터 쓰기", bundle: .module)
                    }
                    .buttonStyle(.geunal(.secondary, height: 52))
                    .accessibilityIdentifier("restore.later")
                }
                Button {
                    if model.mode == .guestReturn {
                        onStartOver()
                    } else {
                        confirming = true
                        Task { await model.loadBackups() }
                    }
                } label: {
                    startOver
                }
                .buttonStyle(.geunal(.destructive, height: 48))
                .accessibilityIdentifier("restore.startOver")
                Button {
                    dismiss()
                } label: {
                    Text("이전 기기에서 찾아볼게요", bundle: .module)
                }
                .buttonStyle(.geunal(.text, height: 52))
            }
        }
    }

    private var title: Text {
        switch model.mode {
        case .guestReturn: Text("복구 코드 없이는 게스트 계정을 찾을 수 없어요", bundle: .module)
        case .offer: Text("복구 코드 없이는 백업을 열 수 없어요", bundle: .module)
        case .deviceChange: Text("복구 코드 없이는 되찾을 수 없어요", bundle: .module)
        }
    }

    private var first: Text {
        switch model.mode {
        case .guestReturn:
            Text(
                "게스트 계정은 복구 코드가 계정을 찾는 유일한 방법이에요. 그날어때도 코드를 모르고, 운영자도 찾아 드릴 수 없어요. 이전 기기가 아직 있다면 그 기기의 나 › 복구 코드에서 볼 수 있어요.",
                bundle: .module)
        case .offer:
            Text(
                "백업은 이 코드로만 열리고, 그날어때도 열 수 없어요. 코드를 적은 종이가 집에 있다면 나중에 넣어도 돼요. 그동안 캘린더는 이 기기에서 쓰고, 백업은 코드를 넣을 때까지 쉬어요.",
                bundle: .module)
        case .deviceChange:
            Text("백업은 이 코드로만 열리고, 그날어때도 열 수 없어요. 이전 기기가 아직 있다면 그 기기의 나 › 복구 코드에서 볼 수 있어요.", bundle: .module)
        }
    }

    private var second: Text {
        model.mode == .guestReturn
            ? Text("새 게스트 계정으로 시작하면 이전 계정의 일정, 그룹, 백업은 돌아오지 않아요.", bundle: .module)
            : Text("새로 시작하면 이 계정의 백업은 지워지고, 그룹에는 새 기기로 다시 들어가요. 그룹 캘린더와 대화는 지금부터 다시 쌓여요.", bundle: .module)
    }

    private var startOver: Text {
        model.mode == .guestReturn
            ? Text("새 게스트 계정으로 시작", bundle: .module) : Text("백업 없이 새로 시작", bundle: .module)
    }

    // MARK: The confirmation

    private var confirmation: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            heading(Text("백업을 지우고 새로 시작할까요?", bundle: .module))
            confirmationText
                .font(.system(size: 15))
                .lineSpacing(4)
                .foregroundStyle(GeunalColor.ink.opacity(0.85))
                .fixedSize(horizontal: false, vertical: true)
            card
            if !model.screenLocked {
                Text("화면 잠금을 켜 두면 다른 사람이 지우지 못해요.", bundle: .module)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("restore.noScreenLock")
            }
            Spacer(minLength: 0)
            VStack(spacing: Space.xs) {
                Button(action: onStartOver) {
                    confirmTitle
                }
                .buttonStyle(.geunal(.destructiveFilled))
                .disabled(model.isWorking)
                .accessibilityIdentifier("restore.confirmFresh")
                Button {
                    confirming = false
                } label: {
                    Text("지우지 않을게요", bundle: .module)
                }
                .buttonStyle(.geunal(.text, height: 52))
                .accessibilityIdentifier("restore.keepBackups")
            }
        }
    }

    /// The count and the last time when the backups could be read; without them the sheet still says what goes.
    private var confirmationText: Text {
        let rest =
            model.mode == .offer
            ? Text("그룹에는 새 기기로 다시 들어가고, 그룹 캘린더와 대화는 지금부터 다시 쌓여요.", bundle: .module)
            : Text("이전 기기는 로그아웃되고, 그룹에는 새 기기로 다시 들어가요.", bundle: .module)
        guard let backups = model.backupsToDelete, let last = backups.map(\.createdAt).max() else {
            return Text("이 계정의 백업을 지우고, 새 복구 코드와 새 열쇠를 만들어요.", bundle: .module) + Text(verbatim: " ") + rest
        }
        let count = backups.count
        let time = timeText(last)
        return Text("이 계정의 백업 \(count)개(마지막 \(time))를 지우고, 새 복구 코드와 새 열쇠를 만들어요.", bundle: .module)
            + Text(verbatim: " ") + rest
    }

    @ViewBuilder
    private var card: some View {
        switch model.freshProblem {
        case .otherAccount:
            NoticeCard(systemImage: "exclamationmark.triangle", text: otherAccountText, tone: .problem)
                .accessibilityIdentifier("restore.otherAccount")
        case .offline:
            NoticeCard(
                systemImage: "wifi.slash", text: Text("연결이 없어요. 연결되면 다시 시도할게요.", bundle: .module),
                tone: .offline)
        case .failed:
            NoticeCard(
                systemImage: "exclamationmark.triangle",
                text: Text("지금은 할 수 없어요. 잠시 뒤에 다시 시도해 주세요.", bundle: .module), tone: .problem)
        case nil:
            NoticeCard(
                systemImage: "exclamationmark.triangle",
                text: Text("되돌릴 수 없어요. 지운 백업은 복구 코드를 나중에 찾아도 열 수 없어요.", bundle: .module),
                tone: .problem)
        }
    }

    private var otherAccountText: Text {
        model.proofProvider == .kakao
            ? Text("이 계정에 연결된 카카오 계정으로 다시 확인해 주세요. 다른 계정으로는 새로 시작할 수 없어요.", bundle: .module)
            : Text("이 계정에 연결된 Apple 계정으로 다시 확인해 주세요. 다른 계정으로는 새로 시작할 수 없어요.", bundle: .module)
    }

    private var confirmTitle: Text {
        let kakao = model.proofProvider == .kakao
        if model.freshProblem == .otherAccount {
            return kakao ? Text("카카오로 다시 확인", bundle: .module) : Text("Apple로 다시 확인", bundle: .module)
        }
        if model.needsProof {
            return kakao
                ? Text("카카오로 다시 확인하고 새로 시작", bundle: .module) : Text("Apple로 다시 확인하고 새로 시작", bundle: .module)
        }
        if let count = model.backupsToDelete?.count, count > 0 {
            return Text("백업 \(count)개 지우고 새로 시작", bundle: .module)
        }
        return Text("백업 지우고 새로 시작", bundle: .module)
    }

    private func heading(_ text: Text) -> some View {
        text
            .font(.system(size: 22, weight: .semibold))
            .tracking(-0.44)
            .foregroundStyle(GeunalColor.ink)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }
}

/// "이 기기에 다른 계정의 일정이 남아 있어요" (m1.md screen 24): the two accounts' events are never merged, and the
/// sheet only closes with an answer.
public struct KeepLocalSheet: View {
    let local: LocalData
    let onChoose: (LocalDataChoice) -> Void

    public init(local: LocalData, onChoose: @escaping (LocalDataChoice) -> Void) {
        self.local = local
        self.onChoose = onChoose
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            Text("이 기기에 다른 계정의 일정이 남아 있어요", bundle: .module)
                .font(.system(size: 22, weight: .semibold))
                .tracking(-0.44)
                .foregroundStyle(GeunalColor.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(
                "전에 이 기기를 쓰던 계정의 일정 \(Int(local.events))개, 사람 \(Int(local.people))명이에요. 내 계정으로 가져오거나 지울 수 있어요. 두 계정의 일정을 섞어 두지는 않아요.",
                bundle: .module
            )
            .font(.system(size: 16))
            .lineSpacing(5)
            .foregroundStyle(GeunalColor.ink.opacity(0.85))
            .fixedSize(horizontal: false, vertical: true)
            Text("가져오면 내 일정이 되어 백업에 들어가고, 그룹에는 비어 있는지만 보여요. 지우면 이 기기에서만 사라지고, 그 계정의 백업은 그대로예요.", bundle: .module)
                .font(.system(size: 14))
                .lineSpacing(4)
                .foregroundStyle(GeunalColor.ink.opacity(0.85))
                .fixedSize(horizontal: false, vertical: true)
                .padding(Space.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.input))
            Spacer(minLength: 0)
            VStack(spacing: Space.xs) {
                Button {
                    onChoose(.keep)
                } label: {
                    Text("내 계정으로 가져오기", bundle: .module)
                }
                .buttonStyle(.geunal(.primary))
                .accessibilityIdentifier("keepLocal.keep")
                Button {
                    onChoose(.discard)
                } label: {
                    Text("지우고 시작하기", bundle: .module)
                }
                .buttonStyle(.geunal(.destructive))
                .accessibilityIdentifier("keepLocal.discard")
            }
        }
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.top, Space.l)
        .padding(.bottom, Space.s)
        .sheetChrome(height: 472, dismissible: false)
    }
}
