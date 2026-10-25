import DesignSystem
import SwiftUI

/// The one M0 screen (docs/parts/ios.md §10): whether the core opened, which SQLCipher it runs, and how the first
/// sync went. Plain white and ink, a grouped card per step (docs/design/README.md §1).
struct CoreStatusView: View {
    let model: CoreStatusModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.l) {
                Text("코어 상태")
                    .typeStyle(.largeTitle)
                    .foregroundStyle(GeunalColor.ink)
                    .padding(.top, Space.xl)

                Text("Rust 코어를 열고 첫 동기화까지 돌려 봅니다.")
                    .typeStyle(.secondary)
                    .foregroundStyle(GeunalColor.secondaryText)

                VStack(spacing: 0) {
                    row(title: "저장소 열기", step: model.open, identifier: "status.open")
                    Divider().overlay(GeunalColor.separator)
                    row(title: "SQLCipher", step: model.cipher, identifier: "status.cipher")
                    Divider().overlay(GeunalColor.separator)
                    row(title: "동기화", step: model.sync, identifier: "status.sync")
                }
                .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.card))

                Text("API \(AppCore.apiBase)")
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.tertiaryText)
                    .monospacedDigit()

                Button {
                    Task { await model.start() }
                } label: {
                    Text("다시 시도")
                        .typeStyle(.bodyStrong)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Space.m)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .tint(GeunalColor.ink)
                .disabled(model.isRunning)
                .accessibilityIdentifier("retry")
            }
            .padding(.horizontal, Space.l)
        }
        .background(GeunalColor.background)
        .task { await model.start() }
    }

    private func row(title: LocalizedStringKey, step: CoreStatusModel.Step, identifier: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.m) {
            Text(title)
                .typeStyle(.bodyStrong)
                .foregroundStyle(GeunalColor.ink)
            Spacer(minLength: Space.s)
            Text(step.text)
                .typeStyle(.body)
                .foregroundStyle(color(for: step))
                .multilineTextAlignment(.trailing)
                .monospacedDigit()
                .accessibilityIdentifier(identifier)
        }
        .padding(.horizontal, Space.l)
        .padding(.vertical, Space.m)
    }

    private func color(for step: CoreStatusModel.Step) -> Color {
        switch step {
        case .waiting, .running: GeunalColor.secondaryText
        case .done: GeunalColor.ink
        case .failed: GeunalColor.sunday
        }
    }
}
