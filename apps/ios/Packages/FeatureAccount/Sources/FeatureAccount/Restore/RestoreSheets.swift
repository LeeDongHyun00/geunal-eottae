import DesignSystem
import GeunalCore
import SwiftUI

/// "복구 코드가 없을 때" (m1.md screen 21): the code is the only key, so starting over loses the old backup (device
/// change) or the old guest account (guest return).
struct NoCodeSheet: View {
    let mode: RestoreModel.Mode
    let screenLocked: Bool
    let onStartOver: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            title
                .font(.system(size: 22, weight: .semibold))
                .tracking(-0.44)
                .foregroundStyle(GeunalColor.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Group {
                first
                second
            }
            .font(.system(size: 15))
            .lineSpacing(4)
            .foregroundStyle(GeunalColor.ink.opacity(0.85))
            .fixedSize(horizontal: false, vertical: true)
            if asksOwner && !screenLocked {
                Text("화면 잠금을 켜 두면 다른 사람이 지우지 못해요.", bundle: .module)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("restore.noScreenLock")
            }
            Spacer(minLength: 0)
            VStack(spacing: Space.xs) {
                Button(action: onStartOver) {
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
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.top, Space.l)
        .padding(.bottom, Space.s)
        .sheetChrome(height: asksOwner && !screenLocked ? 448 : 412)
    }

    /// Starting fresh deletes the account's backups and passes the OS prompt; a new guest account deletes nothing.
    private var asksOwner: Bool { mode != .guestReturn }

    private var title: Text {
        mode == .guestReturn
            ? Text("복구 코드 없이는 게스트 계정을 찾을 수 없어요", bundle: .module)
            : Text("복구 코드 없이는 되찾을 수 없어요", bundle: .module)
    }

    private var first: Text {
        mode == .guestReturn
            ? Text(
                "게스트 계정은 복구 코드가 계정을 찾는 유일한 방법이에요. 그날어때도 코드를 모르고, 운영자도 찾아 드릴 수 없어요. 이전 기기가 아직 있다면 그 기기의 나 › 복구 코드에서 볼 수 있어요.",
                bundle: .module)
            : Text("백업은 이 코드로만 열리고, 그날어때도 열 수 없어요. 이전 기기가 아직 있다면 그 기기의 나 › 복구 코드에서 볼 수 있어요.", bundle: .module)
    }

    private var second: Text {
        mode == .guestReturn
            ? Text("새 게스트 계정으로 시작하면 이전 계정의 일정, 그룹, 백업은 돌아오지 않아요.", bundle: .module)
            : Text("새로 시작하면 이 계정의 백업은 지워지고, 그룹에는 새 기기로 다시 들어가요. 그룹 캘린더와 대화는 지금부터 다시 쌓여요.", bundle: .module)
    }

    private var startOver: Text {
        mode == .guestReturn
            ? Text("새 게스트 계정으로 시작", bundle: .module) : Text("백업 없이 새로 시작", bundle: .module)
    }
}

/// "이 기기에 다른 계정의 일정이 남아 있어요" (m1.md screen 24): the two accounts' events are never merged, and the
/// sheet only closes with an answer.
struct KeepLocalSheet: View {
    let local: LocalData
    let onChoose: (LocalDataChoice) -> Void

    var body: some View {
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
