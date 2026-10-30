import DesignSystem
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 복구 코드 (m1.md screen 13, boards/Recovery): the twelve words in three columns, the warning card, the backup line,
/// "복사" and "종이에 적었어요". The words are covered whenever the app leaves the front, a screenshot gets the toast
/// "캡처 대신 종이에 적어 두세요", and a copy leaves the clipboard after 60 seconds and never reaches other devices.
/// Whoever opens it passes the OS prompt first (`DeviceOwnerReason.recoveryCode`).
public struct RecoveryCodeScreen: View {
    @State private var model: RecoveryCodeModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    private let screenLocked: Bool
    private let backupLine: (Date?) -> Text
    private let notify: (String) -> Void

    /// `screenLocked` is `false` on a device without a passcode, where the prompt let the screen open without asking.
    /// `backupLine` words the backup state under the warning; the app has the date style.
    public init(
        model: RecoveryCodeModel, screenLocked: Bool, backupLine: @escaping (Date?) -> Text,
        notify: @escaping (String) -> Void
    ) {
        _model = State(initialValue: model)
        self.screenLocked = screenLocked
        self.backupLine = backupLine
        self.notify = notify
    }

    private let columns = Array(repeating: GridItem(.flexible(), spacing: Space.s), count: 3)

    public var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.m) {
                    Text("복구 코드", bundle: .module)
                        .typeStyle(.largeTitle)
                        .foregroundStyle(GeunalColor.ink)
                        .accessibilityAddTraits(.isHeader)
                    Text("새 기기에서 일정과 대화를 되찾을 때 필요해요. 이 12단어는 그날어때도 모릅니다.", bundle: .module)
                        .typeStyle(.secondary)
                        .foregroundStyle(GeunalColor.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                    switch model.phase {
                    case .loading:
                        ProgressView()
                            .frame(maxWidth: .infinity, minHeight: 200)
                    case .none:
                        NoticeCard(
                            systemImage: "key",
                            text: Text("이 기기에는 아직 복구 코드가 없어요. 계정을 만들 때 생겨요.", bundle: .module),
                            tone: .offline)
                    case .shown:
                        words
                        warning
                        if !screenLocked {
                            Text("화면 잠금을 켜 두면 다른 사람이 복구 코드를 보지 못해요.", bundle: .module)
                                .typeStyle(.caption)
                                .foregroundStyle(GeunalColor.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("recovery.noScreenLock")
                        }
                        backupLine(model.lastBackupAt)
                            .typeStyle(.caption)
                            .foregroundStyle(GeunalColor.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if model.failed {
                        NoticeCard(
                            systemImage: "exclamationmark.circle",
                            text: Text("저장하지 못했어요. 다시 시도해 주세요", bundle: .module), tone: .problem)
                    }
                }
                .padding(.horizontal, Space.l)
                .padding(.bottom, Space.l)
            }
            if model.phase == .shown {
                HStack(spacing: Space.xs) {
                    Button {
                        copy()
                    } label: {
                        Text("복사", bundle: .module)
                    }
                    .buttonStyle(.geunal(.secondary))
                    .frame(maxWidth: 140)
                    .accessibilityIdentifier("recovery.copy")
                    Button {
                        Task {
                            if await model.markSaved() {
                                dismiss()
                            }
                        }
                    } label: {
                        Text("종이에 적었어요", bundle: .module)
                    }
                    .buttonStyle(.geunal(.primary))
                    .accessibilityIdentifier("recovery.saved")
                }
                .padding(.horizontal, Space.l)
                .padding(.bottom, Space.s)
            }
        }
        .background(GeunalColor.background)
        .navigationTitle(Text("복구 코드", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1) }
        }
        // iOS keeps a picture of the screen for the app switcher; the words must not be in it.
        .overlay {
            if scenePhase != .active {
                GeunalColor.background.ignoresSafeArea()
            }
        }
        .task { await model.load() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            notify(String(localized: "캡처 대신 종이에 적어 두세요", bundle: .module))
        }
    }

    private var words: some View {
        LazyVGrid(columns: columns, spacing: Space.s) {
            ForEach(Array(model.words.enumerated()), id: \.offset) { index, word in
                HStack(spacing: Space.xs) {
                    Text(verbatim: "\(index + 1)")
                        .font(.system(size: 13))
                        .monospacedDigit()
                        .foregroundStyle(GeunalColor.tertiaryText)
                    Text(verbatim: word)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(GeunalColor.ink)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, Space.s)
                .frame(height: 48)
                .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.input))
                .accessibilityElement(children: .combine)
            }
        }
        .padding(Space.m)
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityIdentifier("recovery.words")
    }

    private var warning: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(GeunalColor.sunday)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text("코드와 기기를 함께 잃으면 되찾을 수 없어요", bundle: .module)
                    .typeStyle(.bodyStrong)
                    .foregroundStyle(GeunalColor.ink)
                Text("화면 캡처보다 종이에 적어 안전한 곳에 두세요. 운영자도 대신 찾아 드릴 수 없어요.", bundle: .module)
                    .typeStyle(.secondary)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(RoundedRectangle(cornerRadius: Radius.card).strokeBorder(GeunalColor.separator, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    /// The clipboard keeps the words for a minute and on this device only (m1.md screen 13 "동작").
    private func copy() {
        UIPasteboard.general.setItems(
            [[UTType.plainText.identifier: model.copyText]],
            options: [.expirationDate: Date().addingTimeInterval(60), .localOnly: true])
        notify(String(localized: "복사했어요. 1분 뒤 클립보드에서 지워져요", bundle: .module))
    }
}
