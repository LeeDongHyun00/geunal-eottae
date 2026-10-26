import DesignSystem
import GeunalCore
import SwiftUI

/// 계정 삭제 (m1.md screen 22, boards/DeleteAccount): what the server deletes, what stays on this device, the notes,
/// the red "계정 삭제" and "그냥 둘게요". The sheet asks for "삭제" typed out, then the provider's sign-in for a Kakao
/// or Apple account. Offline the button says it waits for a connection.
public struct DeleteAccountScreen: View {
    @State private var model: DeleteAccountModel
    @State private var confirming = false
    @Environment(\.dismiss) private var dismiss
    private let online: Bool
    private let onDeleted: () -> Void

    public init(model: DeleteAccountModel, online: Bool, onDeleted: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.online = online
        self.onDeleted = onDeleted
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                Text("계정 삭제", bundle: .module)
                    .typeStyle(.largeTitle)
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("삭제하면 되돌릴 수 없어요. 먼저 무엇이 지워지고 무엇이 남는지 확인하세요.", bundle: .module)
                    .typeStyle(.secondary)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                card(
                    Text("서버에서 지워지는 것", bundle: .module),
                    [
                        Text(
                            "**계정과 기기 등록.** 카카오·Apple 연결도 풀려요. 카카오·Apple 계정은 삭제 직전에 그 계정으로 로그인을 한 번 더 받아요. 게스트는 기기의 복구 코드 자료로 확인해서 더 할 일이 없어요.",
                            bundle: .module),
                        Text("**암호화 백업.** 복구 코드로도 다시 받을 수 없어요.", bundle: .module),
                        Text("**그룹 멤버십.** 이미 정해진 약속은 다른 멤버에게 남고, 내 이름은 ‘나간 멤버’로 보여요.", bundle: .module),
                        Text("**그룹에 보낸 사진·파일.** 보관 기간이 남았어도 지워져요.", bundle: .module),
                    ])
                card(
                    Text("이 기기에 남는 것", bundle: .module),
                    [
                        Text("내 일정과 캘린더, 사람·생일, 교대 템플릿은 이 기기에 그대로 있어요. 그룹 없는 캘린더로 계속 쓸 수 있어요.", bundle: .module)
                    ])
                Text("접속 기록은 법에 따라 3개월 보관한 뒤 지워요. 플러스 구독은 스토어에서 해지해 주세요.", bundle: .module)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                VStack(spacing: Space.xs) {
                    Button {
                        confirming = true
                    } label: {
                        if online {
                            Text("계정 삭제", bundle: .module)
                        } else {
                            Text("연결되면 지울 수 있어요", bundle: .module)
                        }
                    }
                    .buttonStyle(.geunal(.destructive))
                    .disabled(!online)
                    .accessibilityIdentifier("delete.start")
                    Button {
                        dismiss()
                    } label: {
                        Text("그냥 둘게요", bundle: .module)
                    }
                    .buttonStyle(.geunal(.text))
                    .accessibilityIdentifier("delete.keep")
                }
                .padding(.top, Space.s)
            }
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.xxl)
        }
        .background(GeunalColor.groupedBackground)
        .navigationTitle(Text("계정 삭제", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
        .sheet(isPresented: $confirming) {
            DeleteConfirmSheet(model: model, online: online) {
                confirming = false
                onDeleted()
            }
        }
    }

    private func card(_ title: Text, _ items: [Text]) -> some View {
        VStack(alignment: .leading, spacing: Space.s) {
            title
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(GeunalColor.secondaryText)
            ForEach(items.indices, id: \.self) { index in
                HStack(alignment: .firstTextBaseline, spacing: Space.xs) {
                    if items.count > 1 {
                        Text(verbatim: "•")
                            .foregroundStyle(GeunalColor.ink)
                            .accessibilityHidden(true)
                    }
                    items[index]
                        .typeStyle(.body)
                        .foregroundStyle(GeunalColor.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .combine)
    }
}

/// "하린님의 계정을 삭제할까요?": the warning, for a Kakao or Apple account why it signs in once more, and the field
/// that wants "삭제".
private struct DeleteConfirmSheet: View {
    let model: DeleteAccountModel
    let online: Bool
    let onDeleted: () -> Void

    private var provider: Provider? { model.provider }

    var body: some View {
        @Bindable var model = model
        VStack(alignment: .leading, spacing: Space.m) {
            Text("\(model.displayName)님의 계정을 삭제할까요?", bundle: .module)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .accessibilityAddTraits(.isHeader)
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                Image(systemName: "exclamationmark.triangle")
                    .foregroundStyle(GeunalColor.sunday)
                    .accessibilityHidden(true)
                Text("백업과 그룹 멤버십이 바로 지워지고 되돌릴 수 없어요. 이 기기의 일정은 남아요.", bundle: .module)
                    .typeStyle(.body)
                    .foregroundStyle(GeunalColor.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !model.screenLocked {
                Text("화면 잠금을 켜 두면 다른 사람이 지우지 못해요.", bundle: .module)
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("delete.noScreenLock")
            }
            if model.problem == .reauthRequired, let card = reauthCard {
                NoticeCard(systemImage: "exclamationmark.circle", text: card, tone: .problem)
                    .accessibilityIdentifier("delete.reauth")
            } else if let reason = providerReason {
                NoticeCard(systemImage: "person.badge.key", text: reason, tone: .offline)
            }
            VStack(alignment: .leading, spacing: Space.xs) {
                Text("확인을 위해 ‘\(DeleteAccountModel.word)’라고 적어 주세요", bundle: .module)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(GeunalColor.secondaryText)
                TextField(text: $model.typed) {
                    Text(verbatim: DeleteAccountModel.word)
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(size: 17))
                .padding(.horizontal, Space.m)
                .frame(height: 52)
                .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.input))
                .accessibilityIdentifier("delete.word")
            }
            if let problem = model.problem, problem != .reauthRequired {
                NoticeCard(systemImage: "exclamationmark.circle", text: Self.text(problem), tone: .problem)
            }
            Button {
                Task {
                    if await model.delete(online: online) {
                        onDeleted()
                    }
                }
            } label: {
                confirmLabel
            }
            .buttonStyle(.geunal(.destructive))
            .disabled(!model.canConfirm)
            .accessibilityIdentifier("delete.confirm")
        }
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.top, Space.l)
        .padding(.bottom, Space.s)
        .sheetChrome(height: nil)
    }

    private var providerReason: Text? {
        switch provider {
        case .kakao:
            Text("카카오 계정이라 ‘삭제’를 적은 뒤 카카오 로그인을 한 번 더 받아요. 세션만 훔친 사람이 계정을 지우지 못하게 하려는 거예요.", bundle: .module)
        case .apple:
            Text("Apple 계정이라 ‘삭제’를 적은 뒤 Apple 로그인을 한 번 더 받아요. 세션만 훔친 사람이 계정을 지우지 못하게 하려는 거예요.", bundle: .module)
        case nil:
            nil
        }
    }

    /// m1.md screen 22 "오류": the linked account, not the one the provider happened to sign in.
    private var reauthCard: Text? {
        switch provider {
        case .kakao: Text("이 계정에 연결된 카카오 계정으로 다시 확인해 주세요. 다른 계정으로 로그인하면 지울 수 없어요.", bundle: .module)
        case .apple: Text("이 계정에 연결된 Apple 계정으로 다시 확인해 주세요. 다른 계정으로 로그인하면 지울 수 없어요.", bundle: .module)
        case nil: nil
        }
    }

    private var confirmLabel: Text {
        if model.preparing {
            return Text("준비하는 중…", bundle: .module)
        }
        if model.isWorking {
            return Text("지우는 중", bundle: .module)
        }
        if model.problem == .reauthRequired, model.canConfirm {
            switch provider {
            case .kakao: return Text("카카오로 다시 확인", bundle: .module)
            case .apple: return Text("Apple로 다시 확인", bundle: .module)
            case nil: break
            }
        }
        guard model.canConfirm else {
            switch provider {
            case .kakao: return Text("‘삭제’를 적으면 카카오로 확인해요", bundle: .module)
            case .apple: return Text("‘삭제’를 적으면 Apple로 확인해요", bundle: .module)
            case nil: return Text("‘삭제’를 적어 주세요", bundle: .module)
            }
        }
        switch provider {
        case .kakao: return Text("카카오로 확인하고 삭제", bundle: .module)
        case .apple: return Text("Apple로 확인하고 삭제", bundle: .module)
        case nil: return Text("삭제", bundle: .module)
        }
    }

    static func text(_ problem: DeleteAccountModel.Problem) -> Text {
        switch problem {
        case .offline: Text("연결되면 지울 수 있어요.", bundle: .module)
        case .reauthRequired, .failed: Text("지우지 못했어요. 다시 시도해 주세요.", bundle: .module)
        case .recoveryMissing: Text("지울 준비가 아직 끝나지 않았어요. 잠시 뒤에 다시 시도해 주세요.", bundle: .module)
        }
    }
}
