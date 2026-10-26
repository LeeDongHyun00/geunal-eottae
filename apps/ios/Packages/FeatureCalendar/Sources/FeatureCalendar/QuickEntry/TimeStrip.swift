import DesignSystem
import SwiftUI

/// The 0–24 strip of the quick entry (m1.md screen 5, components.md §8 "선택 범위"): drag across it to choose a range
/// in half hours, then drag either end to adjust. A tap picks the hour under the finger.
struct TimeStrip: View {
    @Binding var range: Range<Int>?
    private let slots = QuickEntryModel.slots

    @State private var drag: Drag?

    private enum Drag {
        case new(anchor: Int)
        case lower
        case upper
    }

    var body: some View {
        VStack(spacing: 6) {
            GeometryReader { geometry in
                let width = geometry.size.width
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: Radius.input)
                        .fill(GeunalColor.emptyCell)
                    HourTicks()
                    if let range {
                        let step = width / CGFloat(slots)
                        RoundedRectangle(cornerRadius: Radius.input)
                            .fill(GeunalColor.ink.opacity(0.08))
                            .overlay(
                                RoundedRectangle(cornerRadius: Radius.input).strokeBorder(GeunalColor.ink, lineWidth: 2)
                            )
                            .frame(width: max(CGFloat(range.count) * step, 8))
                            .offset(x: CGFloat(range.lowerBound) * step)
                        Handle()
                            .offset(x: CGFloat(range.lowerBound) * step - 10)
                        Handle()
                            .offset(x: CGFloat(range.upperBound) * step - 10)
                    } else {
                        Text("끌어서 시간 고르기", bundle: .module)
                            .font(.system(size: 13))
                            .foregroundStyle(GeunalColor.secondaryText)
                            .frame(maxWidth: .infinity)
                    }
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in changed(value, width: width) }
                        .onEnded { value in ended(value, width: width) }
                )
            }
            .frame(height: 44)
            HStack {
                ForEach([0, 6, 12, 18, 24], id: \.self) { hour in
                    Text(verbatim: String(hour))
                    if hour < 24 {
                        Spacer()
                    }
                }
            }
            .font(.system(size: 11))
            .monospacedDigit()
            .foregroundStyle(GeunalColor.tertiaryText)
            .accessibilityHidden(true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("시간", bundle: .module))
        .accessibilityValue(Text(verbatim: valueText))
        .accessibilityAdjustableAction { direction in
            adjust(direction)
        }
        .accessibilityAction(named: Text("30분 늘리기", bundle: .module)) { resize(by: 1) }
        .accessibilityAction(named: Text("30분 줄이기", bundle: .module)) { resize(by: -1) }
        .accessibilityAction(named: Text("종일로 하기", bundle: .module)) { range = nil }
        .accessibilityIdentifier("quickEntry.strip")
    }

    private var valueText: String {
        guard let range else { return String(localized: "종일", bundle: .module) }
        return DayText.range(start: UInt16(range.lowerBound * 30), end: UInt16(range.upperBound * 30))
    }

    private func slot(at x: CGFloat, width: CGFloat) -> Int {
        min(max(Int(x / width * CGFloat(slots)), 0), slots - 1)
    }

    private func changed(_ value: DragGesture.Value, width: CGFloat) {
        let current = slot(at: value.location.x, width: width)
        if drag == nil {
            drag = startDrag(at: value.startLocation.x, width: width)
        }
        switch drag {
        case .new(let anchor):
            // A drag that has not moved yet is a tap; `ended` decides.
            guard abs(value.translation.width) > 4 else { return }
            range = min(anchor, current)..<(max(anchor, current) + 1)
        case .lower:
            guard let range else { return }
            self.range = min(current, range.upperBound - 1)..<range.upperBound
        case .upper:
            guard let range else { return }
            let upper = min(max(current + 1, range.lowerBound + 1), slots)
            self.range = range.lowerBound..<upper
        case nil:
            break
        }
    }

    private func ended(_ value: DragGesture.Value, width: CGFloat) {
        if case .new = drag, abs(value.translation.width) <= 4 {
            // A tap takes the hour it lands on.
            let hour = slot(at: value.location.x, width: width) / 2 * 2
            range = hour..<min(hour + 2, slots)
        }
        drag = nil
    }

    /// A press within a handle's reach adjusts that end; anywhere else starts a new range.
    private func startDrag(at x: CGFloat, width: CGFloat) -> Drag {
        if let range {
            let step = width / CGFloat(slots)
            let lower = CGFloat(range.lowerBound) * step
            let upper = CGFloat(range.upperBound) * step
            if abs(x - upper) <= 22 {
                return .upper
            }
            if abs(x - lower) <= 22 {
                return .lower
            }
        }
        return .new(anchor: slot(at: x, width: width))
    }

    /// Swipe up and down with VoiceOver: the range moves by half an hour, or 09:00–10:00 appears.
    private func adjust(_ direction: AccessibilityAdjustmentDirection) {
        guard let current = range else {
            range = 18..<20
            return
        }
        let delta = direction == .increment ? 1 : -1
        let lower = current.lowerBound + delta
        guard lower >= 0, current.upperBound + delta <= slots else { return }
        range = lower..<(current.upperBound + delta)
    }

    private func resize(by delta: Int) {
        guard let current = range else {
            range = 18..<20
            return
        }
        let upper = min(max(current.upperBound + delta, current.lowerBound + 1), slots)
        range = current.lowerBound..<upper
    }
}

/// A line every hour, a little stronger every six hours.
private struct HourTicks: View {
    var body: some View {
        Canvas { context, size in
            for hour in 1..<24 {
                let x = size.width * CGFloat(hour) / 24
                var line = Path()
                line.move(to: CGPoint(x: x, y: 0))
                line.addLine(to: CGPoint(x: x, y: size.height))
                context.stroke(line, with: .color(GeunalColor.separator), lineWidth: hour % 6 == 0 ? 1.5 : 1)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Radius.input))
        .allowsHitTesting(false)
    }
}

/// A selection handle (components.md §8: 20 × 44).
private struct Handle: View {
    var body: some View {
        Capsule()
            .fill(GeunalColor.ink)
            .frame(width: 4, height: 24)
            .frame(width: 20, height: 44)
            .contentShape(Rectangle())
            .allowsHitTesting(false)
    }
}
