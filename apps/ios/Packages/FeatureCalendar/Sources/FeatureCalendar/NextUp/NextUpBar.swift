import DesignSystem
import GeunalCore
import SwiftUI

/// The next-up bar: ‹ thumbnail, title and when › (docs/design/README.md §5, components.md §7). With `chrome` it draws
/// its own floating capsule for iOS 17 and 18; iOS 26 puts it in the tab view's bottom accessory, which brings the
/// Liquid Glass capsule itself.
public struct NextUpBar: View {
    private let model: NextUpModel
    private let chrome: Bool
    private let onOpen: (UpNext) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(model: NextUpModel, chrome: Bool, onOpen: @escaping (UpNext) -> Void) {
        self.model = model
        self.chrome = chrome
        self.onOpen = onOpen
    }

    public var body: some View {
        HStack(spacing: 0) {
            IconButton(systemImage: "chevron.left", label: Text("이전 할 일", bundle: .module)) {
                model.back()
            }
            .disabled(!model.canGoBack)
            .opacity(model.canGoBack ? 1 : 0.3)
            Group {
                if let item = model.current {
                    Button {
                        onOpen(item.source)
                    } label: {
                        content(item)
                    }
                    .buttonStyle(.pressable)
                    .id(model.index)
                    .transition(reduceMotion ? .opacity : .push(from: .trailing))
                } else {
                    Text("다가오는 일정이 없어요", bundle: .module)
                        .font(.system(size: 15))
                        .foregroundStyle(GeunalColor.secondaryText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .animation(.easeOut(duration: Motion.NextUp.duration), value: model.index)
            IconButton(systemImage: "chevron.right", label: Text("다음 할 일", bundle: .module)) {
                model.forward()
            }
            .disabled(!model.canGoForward)
            .opacity(model.canGoForward ? 1 : 0.3)
        }
        .padding(.horizontal, 4)
        .frame(height: Metrics.NextUpBar.height)
        .background {
            if chrome {
                Capsule()
                    .fill(.regularMaterial)
                    .overlay(Capsule().strokeBorder(Color.black.opacity(0.07), lineWidth: 1))
                    .shadow(color: .black.opacity(0.10), radius: 16, y: 12)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("nextUp")
    }

    private func content(_ item: NextUpModel.Item) -> some View {
        HStack(spacing: Space.s) {
            Image(systemName: item.isBirthday ? "gift.fill" : "calendar")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: Metrics.NextUpBar.thumb, height: Metrics.NextUpBar.thumb)
                .background(thumbColor(item), in: RoundedRectangle(cornerRadius: Metrics.NextUpBar.thumbRadius))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: item.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(GeunalColor.ink)
                    .lineLimit(1)
                Text(verbatim: item.detail)
                    .font(.system(size: 13))
                    .monospacedDigit()
                    .foregroundStyle(GeunalColor.secondaryText)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func thumbColor(_ item: NextUpModel.Item) -> Color {
        item.isBirthday ? GroupColor.blossom.representative : CalendarColors.marker(item.color)
    }
}
