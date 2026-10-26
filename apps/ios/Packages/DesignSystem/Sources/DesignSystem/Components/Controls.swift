import SwiftUI

/// The consent checkbox (components.md §4: 24 pt, radius 6), drawn as a whole row so the label is part of the target.
/// It reads as a checkbox to VoiceOver: a button with the selected trait.
public struct CheckboxRow: View {
    @Binding private var isOn: Bool
    private let title: Text
    private let detail: Text?

    public init(isOn: Binding<Bool>, title: Text, detail: Text? = nil) {
        _isOn = isOn
        self.title = title
        self.detail = detail
    }

    public var body: some View {
        Button {
            isOn.toggle()
        } label: {
            HStack(spacing: Space.s) {
                RoundedRectangle(cornerRadius: Metrics.Checkbox.radius)
                    .fill(isOn ? GeunalColor.ink : GeunalColor.background)
                    .overlay {
                        RoundedRectangle(cornerRadius: Metrics.Checkbox.radius)
                            .strokeBorder(isOn ? GeunalColor.ink : GeunalColor.tertiaryText, lineWidth: 1.5)
                    }
                    .overlay {
                        if isOn {
                            Image(systemName: "checkmark")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(GeunalColor.background)
                        }
                    }
                    .frame(width: Metrics.Checkbox.size, height: Metrics.Checkbox.size)
                VStack(alignment: .leading, spacing: 2) {
                    title
                        .typeStyle(.bodyStrong)
                        .foregroundStyle(GeunalColor.ink)
                    if let detail {
                        detail
                            .typeStyle(.caption)
                            .foregroundStyle(GeunalColor.secondaryText)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
            .frame(minHeight: 56)
            .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: 16))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }
}

/// A switch with the calendar's ink tint (components.md §4). Inside a group the caller tints it with the group color.
public struct GeunalToggle: View {
    @Binding private var isOn: Bool
    private let title: Text
    private let detail: Text?

    public init(isOn: Binding<Bool>, title: Text, detail: Text? = nil) {
        _isOn = isOn
        self.title = title
        self.detail = detail
    }

    public var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                title
                    .typeStyle(.body)
                    .foregroundStyle(GeunalColor.ink)
                if let detail {
                    detail
                        .typeStyle(.caption)
                        .foregroundStyle(GeunalColor.secondaryText)
                }
            }
        }
        .tint(GeunalColor.ink)
        .padding(.horizontal, Space.m)
        .frame(minHeight: Metrics.Row.setting)
    }
}

/// The segmented control of components.md §4: a `fill` track 40 pt high and a white thumb with a small shadow. It reads
/// as a row of buttons where one is selected, like the system's segmented picker.
public struct SegmentedControl<Value: Hashable>: View {
    @Binding private var selection: Value
    private let options: [(value: Value, label: Text)]

    public init(selection: Binding<Value>, options: [(value: Value, label: Text)]) {
        _selection = selection
        self.options = options
    }

    public var body: some View {
        HStack(spacing: 0) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                let selected = option.value == selection
                Button {
                    selection = option.value
                } label: {
                    option.label
                        .typeStyle(.pillLabel)
                        .foregroundStyle(selected ? GeunalColor.ink : GeunalColor.secondaryText)
                        .padding(.horizontal, Space.m)
                        .frame(maxHeight: .infinity)
                        .background {
                            if selected {
                                Capsule()
                                    .fill(GeunalColor.background)
                                    .shadow(color: .black.opacity(0.12), radius: 1.5, y: 1)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
        .padding(Metrics.Segment.padding)
        .frame(height: Metrics.Segment.height)
        .background(GeunalColor.fill, in: Capsule())
    }
}

/// A selection chip (components.md §3): `fill` and ink when off, ink and white when on. Used for repeat rules and
/// filters; a set of them with one choice reads as radio buttons through the selected trait.
public struct SelectChip: View {
    private let label: Text
    private let isOn: Bool
    private let action: () -> Void

    public init(_ label: Text, isOn: Bool, action: @escaping () -> Void) {
        self.label = label
        self.isOn = isOn
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            label
                .typeStyle(.pillLabel)
                .foregroundStyle(isOn ? GeunalColor.background : GeunalColor.ink)
                .padding(.horizontal, Space.s)
                .frame(minHeight: Metrics.Chip.select)
                .background(isOn ? GeunalColor.ink : GeunalColor.fill, in: Capsule())
                .frame(minHeight: Metrics.minHitTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }
}

/// A status badge (components.md §3): a group's first tint with ink text, or ink with white text.
public struct StatusBadge: View {
    public enum Tone: Sendable {
        case group(GroupColor)
        case ink
    }

    private let label: Text
    private let tone: Tone

    public init(_ label: Text, tone: Tone) {
        self.label = label
        self.tone = tone
    }

    public var body: some View {
        label
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(foreground)
            .padding(.horizontal, Space.xs)
            .frame(minHeight: Metrics.Chip.badge)
            .background(background, in: Capsule())
    }

    private var foreground: Color {
        switch tone {
        case .group: GeunalColor.ink
        case .ink: GeunalColor.background
        }
    }

    private var background: Color {
        switch tone {
        case .group(let color): color.tint(1)
        case .ink: GeunalColor.ink
        }
    }
}
