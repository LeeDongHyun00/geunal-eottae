import DesignSystem
import GeunalCore
import SwiftUI
import UIKit

/// An overlap group on the week's time axis (m1.md decision 44, boards/WeekOverlap): white with a 1.5 ink border, the
/// count at the top, and on the left one calendar-color line per event, three at most, each as long as its event.
struct OverlapGroupView: View {
    let group: OverlapGroup

    var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: Radius.cell)
                .fill(GeunalColor.background)
            RoundedRectangle(cornerRadius: Radius.cell)
                .strokeBorder(GeunalColor.ink, lineWidth: 1.5)
            HStack(alignment: .top, spacing: 1) {
                ForEach(Array(group.blocks.prefix(OverlapGroup.linesShown).enumerated()), id: \.offset) { _, block in
                    ColorLine(span: group.span(of: block), color: CalendarColors.marker(block.event.color))
                }
            }
            .padding(.leading, 5)
            .padding(.vertical, 5)
            Text("\(group.blocks.count)개", bundle: .module)
                .font(.system(size: 13, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(GeunalColor.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, 4)
                .padding(.trailing, 5)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: group.spokenLabel()))
        .accessibilityAddTraits(.isButton)
    }

    /// One event's line, from its start to its end within the group's height.
    private struct ColorLine: View {
        let span: ClosedRange<Double>
        let color: Color

        var body: some View {
            GeometryReader { geometry in
                let height = geometry.size.height
                Rectangle()
                    .fill(color)
                    .frame(width: 3, height: max(height * (span.upperBound - span.lowerBound), 3))
                    .offset(y: height * span.lowerBound)
            }
            .frame(width: 3)
        }
    }
}

/// The overlapping events' sheet (m1.md decision 44): the day, the group's time and count, a time scale, and one row
/// per event with its time bar. A row opens the editor; "이날 일정 모두 보기" opens the day's quick entry (decision 42).
struct OverlapSheet: View {
    let group: OverlapGroup
    let today: String
    let onEvent: (EventItem) -> Void
    let onDay: () -> Void
    @Environment(\.dismiss) private var dismiss
    @ScaledMetric(relativeTo: .body) private var rowHeight: CGFloat = 56
    @State private var contentHeight: CGFloat?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                header
                VStack(alignment: .leading, spacing: Space.xs) {
                    scale
                    rows
                }
                Button(action: onDay) {
                    Text("이날 일정 모두 보기", bundle: .module)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(GeunalColor.secondaryText)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.pressable)
                .accessibilityIdentifier("overlap.day")
            }
            .padding(.horizontal, Metrics.Sheet.Padding.side)
            .padding(.top, Space.l)
            .padding(.bottom, Space.m)
            .onGeometryChange(for: CGFloat.self, of: \.size.height) { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .sheetChrome(height: sheetHeight)
    }

    /// As tall as the content, up to 90% of the screen, then the content scrolls (as the quick entry, decision 42).
    private var sheetHeight: CGFloat {
        // Before the first layout: about a group of three, so the sheet seldom has to grow.
        guard let contentHeight else { return 420 }
        let window = UIApplication.shared.connectedScenes.lazy.compactMap { $0 as? UIWindowScene }
            .compactMap(\.keyWindow).first
        let measured = contentHeight + (window?.safeAreaInsets.bottom ?? 0)
        guard let screen = window?.bounds.height else { return measured }
        return min(measured, screen * 0.9)
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: DayText.title(group.day, today: today))
                    .font(.system(size: 22, weight: .semibold))
                    .tracking(-0.4)
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                Text(verbatim: group.summary)
                    .font(.system(size: 15))
                    .foregroundStyle(GeunalColor.secondaryText)
            }
            Spacer()
            IconButton(systemImage: "xmark", label: Text("닫기", bundle: .module)) {
                dismiss()
            }
            .padding(.top, -6)
            .padding(.trailing, -12)
        }
    }

    /// Start, middle and end over the rows' bars.
    private var scale: some View {
        let times = group.scale
        return HStack {
            Text(verbatim: DayText.clock(times[0]))
            Spacer()
            Text(verbatim: DayText.clock(times[1]))
            Spacer()
            Text(verbatim: DayText.clock(times[2]))
        }
        .font(.system(size: 13))
        .monospacedDigit()
        .foregroundStyle(GeunalColor.secondaryText)
        .padding(.leading, Space.m + 8 + Space.s)
        .padding(.trailing, Space.m)
        .accessibilityHidden(true)
    }

    private var rows: some View {
        VStack(spacing: 0) {
            ForEach(Array(group.blocks.enumerated()), id: \.offset) { index, block in
                Button {
                    onEvent(block.event)
                } label: {
                    OverlapRow(
                        block: block, span: group.span(of: block), height: rowHeight,
                        separated: index < group.blocks.count - 1)
                }
                .buttonStyle(.pressable)
                .accessibilityLabel(Text(verbatim: Self.spoken(block)))
                .accessibilityIdentifier("overlap.event.\(index)")
            }
        }
        .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.card))
    }

    /// "팀플 회의, 16:00부터 17:00까지": the bar is not read.
    static func spoken(_ block: TimedBlock) -> String {
        let start = DayText.clock(block.startMinute)
        let end = DayText.clock(block.endMinute)
        return String(localized: "\(block.event.title), \(start)부터 \(end)까지", bundle: .module)
    }
}

/// A row of the sheet: the calendar's dot, the title in 15 · 600, the time in 15 on the right, and under them a 6 pt
/// bar with the event's part of the group in its color.
private struct OverlapRow: View {
    let block: TimedBlock
    let span: ClosedRange<Double>
    let height: CGFloat
    let separated: Bool

    var body: some View {
        let color = CalendarColors.marker(block.event.color)
        HStack(alignment: .top, spacing: Space.s) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
                .padding(.top, 6)
            VStack(alignment: .leading, spacing: Space.xs) {
                HStack(alignment: .firstTextBaseline) {
                    Text(verbatim: block.event.title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(GeunalColor.ink)
                        .lineLimit(1)
                    Spacer(minLength: Space.xs)
                    Text(verbatim: DayText.range(start: block.startMinute, end: block.endMinute))
                        .font(.system(size: 15))
                        .monospacedDigit()
                        .foregroundStyle(GeunalColor.secondaryText)
                }
                GeometryReader { geometry in
                    let width = geometry.size.width
                    ZStack(alignment: .leading) {
                        Capsule().fill(GeunalColor.fill)
                        Capsule()
                            .fill(color)
                            .frame(width: max(width * (span.upperBound - span.lowerBound), 6))
                            .offset(x: width * span.lowerBound)
                    }
                }
                .frame(height: 6)
            }
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.s)
        .frame(minHeight: height)
        .overlay(alignment: .bottom) {
            if separated {
                Rectangle().fill(GeunalColor.separator).frame(height: 1)
            }
        }
        .contentShape(Rectangle())
    }
}
