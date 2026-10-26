import SwiftUI

extension View {
    /// A grouped card: `groupedBackground`, radius 20 (components.md §5). Layers are told apart by background color; only
    /// floating things get a shadow.
    public func cardBackground(padding: CGFloat = Space.m) -> some View {
        self
            .padding(padding)
            .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

/// A settings row (components.md §5): title 17, value 17 in `secondaryText`, and a chevron when it leads somewhere.
public struct SettingsRow: View {
    private let title: Text
    private let value: Text?
    private let valueColor: Color
    private let showsChevron: Bool

    public init(_ title: Text, value: Text? = nil, valueColor: Color = GeunalColor.secondaryText, chevron: Bool = true)
    {
        self.title = title
        self.value = value
        self.valueColor = valueColor
        self.showsChevron = chevron
    }

    public var body: some View {
        HStack(spacing: Space.s) {
            title
                .typeStyle(.body)
                .foregroundStyle(GeunalColor.ink)
            Spacer(minLength: Space.s)
            if let value {
                value
                    .typeStyle(.body)
                    .foregroundStyle(valueColor)
                    .monospacedDigit()
                    .multilineTextAlignment(.trailing)
            }
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(GeunalColor.tertiaryText)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: Metrics.Row.setting)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Rows in one grouped card with 1 pt separators between them (components.md §5).
public struct RowGroup<Content: View>: View {
    private let header: Text?
    private let content: Content

    public init(header: Text? = nil, @ViewBuilder content: () -> Content) {
        self.header = header
        self.content = content()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            if let header {
                header
                    .typeStyle(.caption)
                    .fontWeight(.semibold)
                    .foregroundStyle(GeunalColor.secondaryText)
                    .padding(.horizontal, Space.m)
                    .accessibilityAddTraits(.isHeader)
            }
            VStack(spacing: 0) {
                content
            }
            .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
        }
    }
}

/// A banner (components.md §6): a white card with an icon, two lines and a small pill, kept until the user acts.
public struct Banner: View {
    private let systemImage: String
    private let title: Text
    private let detail: Text
    private let actionLabel: Text
    private let action: () -> Void

    public init(systemImage: String, title: Text, detail: Text, actionLabel: Text, action: @escaping () -> Void) {
        self.systemImage = systemImage
        self.title = title
        self.detail = detail
        self.actionLabel = actionLabel
        self.action = action
    }

    public var body: some View {
        HStack(spacing: Space.s) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .regular))
                .foregroundStyle(GeunalColor.ink)
                .frame(width: 40, height: 40)
                .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                title
                    .typeStyle(.bodyStrong)
                    .foregroundStyle(GeunalColor.ink)
                detail
                    .typeStyle(.caption)
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            Spacer(minLength: Space.xs)
            Button(action: action) { actionLabel }
                .buttonStyle(.geunal(.pill))
        }
        .padding(Space.s)
        .background(GeunalColor.background, in: RoundedRectangle(cornerRadius: Radius.card))
        .shadow(color: .black.opacity(0.08), radius: 12, y: 4)
    }
}

/// A notice card inside a screen: grey for reassurance, red for a problem the user has to fix (the restore screen's
/// error states, docs/design/m1.md screen 21).
public struct NoticeCard: View {
    public enum Tone: Sendable {
        case calm
        case problem
        case offline
    }

    private let systemImage: String
    private let text: Text
    private let tone: Tone

    public init(systemImage: String, text: Text, tone: Tone) {
        self.systemImage = systemImage
        self.text = text
        self.tone = tone
    }

    public var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(tone == .problem ? GeunalColor.sunday : GeunalColor.secondaryText)
                .accessibilityHidden(true)
            text
                .font(.system(size: 14))
                .lineSpacing(3)
                .foregroundStyle(GeunalColor.ink.opacity(0.85))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.s)
        .background(background, in: RoundedRectangle(cornerRadius: Radius.input))
        .accessibilityElement(children: .combine)
    }

    private var background: Color {
        switch tone {
        case .calm: .clear
        case .problem: GeunalColor.problemBackground
        case .offline: GeunalColor.fill
        }
    }
}

extension GeunalColor {
    /// The restore screen's error card and cell (`#fbeceb` on the boards), with a dark counterpart in the same hue.
    public static let problemBackground = Color(light: 0xfbeceb, dark: 0x3a1f1d)
}
