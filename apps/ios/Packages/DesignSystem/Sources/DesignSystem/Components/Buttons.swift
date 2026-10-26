import SwiftUI

/// The button kinds of docs/design/components.md §2. Every kind keeps a 44 pt hit area and shrinks to 0.97 while pressed
/// without changing color. Disabled buttons switch to the `fill` and `tertiaryText` colors instead of fading, because
/// their title carries the reason ("단어 5개 더").
public struct GeunalButtonStyle: ButtonStyle {
    public enum Kind: Sendable {
        /// Ink capsule, the one main action of a screen.
        case primary
        /// `fill` capsule next to a primary button.
        case secondary
        /// `fill` capsule with `sunday` text: cancel an appointment, delete the account.
        case destructive
        /// No background, `secondaryText`: "나중에 할게요", "초대 코드가 있어요".
        case text
        /// Small `fill` pill: "붙여넣기", "복사".
        case pill
        /// Small ink pill that starts something: "가져오기", "허용".
        case prominentPill
        /// Small white pill for a step that is done: "가져왔어요 · 캘린더 3개".
        case donePill
    }

    private let kind: Kind
    private let height: CGFloat?

    /// `height` overrides the kind's height where a board asks for another one (the welcome screen's 56 pt button).
    public init(_ kind: Kind, height: CGFloat? = nil) {
        self.kind = kind
        self.height = height
    }

    public func makeBody(configuration: Configuration) -> some View {
        StyledButton(configuration: configuration, kind: kind, height: height)
    }
}

extension ButtonStyle where Self == GeunalButtonStyle {
    public static func geunal(_ kind: GeunalButtonStyle.Kind, height: CGFloat? = nil) -> GeunalButtonStyle {
        GeunalButtonStyle(kind, height: height)
    }
}

private struct StyledButton: View {
    let configuration: ButtonStyleConfiguration
    let kind: GeunalButtonStyle.Kind
    let height: CGFloat?

    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        configuration.label
            .typeStyle(typeStyle)
            .multilineTextAlignment(.center)
            .foregroundStyle(foreground)
            .padding(.horizontal, horizontalPadding)
            .frame(maxWidth: fillsWidth ? .infinity : nil, minHeight: height ?? defaultHeight)
            .background(background, in: Capsule())
            .frame(minHeight: Metrics.minHitTarget)
            .contentShape(Rectangle())
            .scaleEffect(configuration.isPressed && !reduceMotion ? Motion.Press.scale : 1)
            .animation(.easeOut(duration: Motion.Press.duration), value: configuration.isPressed)
    }

    private var defaultHeight: CGFloat {
        switch kind {
        case .primary, .destructive: Metrics.Button.primary
        case .secondary, .text: Metrics.Button.secondary
        case .pill, .prominentPill, .donePill: Metrics.Button.smallPill
        }
    }

    private var fillsWidth: Bool {
        switch kind {
        case .primary, .secondary, .destructive: true
        case .text, .pill, .prominentPill, .donePill: false
        }
    }

    private var horizontalPadding: CGFloat {
        switch kind {
        case .primary, .secondary, .destructive: Space.xl
        case .text: Space.xs
        case .pill, .prominentPill, .donePill: Space.m
        }
    }

    private var typeStyle: TypeStyle {
        switch kind {
        case .primary, .secondary, .destructive: .bodyStrong
        case .text: .secondaryStrong
        case .pill, .prominentPill, .donePill: .pillLabel
        }
    }

    private var foreground: Color {
        guard isEnabled else { return GeunalColor.tertiaryText }
        switch kind {
        case .primary, .prominentPill: return GeunalColor.background
        case .secondary, .pill, .donePill: return GeunalColor.ink
        case .destructive: return GeunalColor.sunday
        case .text: return GeunalColor.secondaryText
        }
    }

    private var background: Color {
        switch kind {
        case .text: return .clear
        case .primary, .prominentPill: return isEnabled ? GeunalColor.ink : GeunalColor.fill
        case .secondary, .destructive, .pill: return GeunalColor.fill
        case .donePill: return isEnabled ? GeunalColor.background : GeunalColor.fill
        }
    }
}

extension TypeStyle {
    /// 15 · 600: text buttons and the permission cards' buttons on the boards.
    public static let secondaryStrong = TypeStyle(
        size: 15, weight: .semibold, tracking: -0.01, relativeTo: .subheadline)
    /// 14 · 600: small pills (components.md §2).
    public static let pillLabel = TypeStyle(size: 14, weight: .semibold, tracking: -0.01, relativeTo: .subheadline)
}

/// An icon-only button with the 44 pt hit area and a spoken name (components.md §1: icon buttons get a name).
public struct IconButton: View {
    private let systemImage: String
    private let label: Text
    private let action: () -> Void

    public init(systemImage: String, label: Text, action: @escaping () -> Void) {
        self.systemImage = systemImage
        self.label = label
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 22, weight: .regular))
                .foregroundStyle(GeunalColor.ink)
                .frame(width: Metrics.Button.icon, height: Metrics.Button.icon)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
