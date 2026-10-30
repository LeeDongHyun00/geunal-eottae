import DesignSystem
import GeunalCore
import SwiftUI

/// The month grid (m1.md screen 4, boards/Month): the weekday row, then a page per month that follows the finger
/// sideways. A day is one button that opens the quick entry.
struct MonthView: View {
    @Bindable var model: MonthModel
    let onDay: (DayCell) -> Void
    /// A tap on an event bar opens the editor.
    let onEvent: (EventItem) -> Void
    /// A tap on the "연차" mark of a long weekend's leave day.
    let onLeave: (DayCell) -> Void
    @ScaledMetric(relativeTo: .caption2) private var cellHeight: CGFloat = Metrics.Grid.monthCell

    var body: some View {
        VStack(spacing: 0) {
            WeekdayRow()
                .padding(.horizontal, Space.xs)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(model.months, id: \.self) { month in
                        MonthPage(
                            weeks: model.weeks(of: month), today: model.today,
                            showsHint: month == model.currentMonth && model.showsFirstRunHint,
                            cellHeight: cellHeight, onDay: onDay, onEvent: onEvent, onLeave: onLeave
                        )
                        .containerRelativeFrame(.horizontal)
                        .task { await model.load(month) }
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $model.visibleMonth)
            .scrollIndicators(.hidden)
            // Six weeks always fit, so a five-week month leaves the last row empty rather than moving the bars below.
            .frame(height: 6 * cellHeight + Space.xl)
        }
    }
}

/// 일 to 토, Sunday red and Saturday blue (tone.md "날짜와 시간"). The cells read their own weekday, so the row is
/// hidden from VoiceOver.
struct WeekdayRow: View {
    var body: some View {
        HStack(spacing: 0) {
            ForEach(1...7, id: \.self) { weekday in
                Text(verbatim: DayText.weekdayName(weekday))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(DayColors.weekday(weekday))
                    .frame(maxWidth: .infinity, minHeight: 24)
            }
        }
        .accessibilityHidden(true)
    }
}

private struct MonthPage: View {
    let weeks: [[DayCell]]
    let today: String
    let showsHint: Bool
    let cellHeight: CGFloat
    let onDay: (DayCell) -> Void
    let onEvent: (EventItem) -> Void
    let onLeave: (DayCell) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ForEach(weeks.indices, id: \.self) { index in
                let week = weeks[index]
                let hasToday = week.contains { $0.day == today }
                HStack(spacing: 0) {
                    ForEach(week, id: \.day) { cell in
                        MonthCellView(
                            cell: cell, today: today, height: cellHeight, action: { onDay(cell) },
                            onEvent: onEvent, onLeave: { onLeave(cell) }
                        )
                        .overlay(alignment: .bottom) {
                            if showsHint && cell.day == today {
                                FirstRunHint()
                                    .fixedSize()
                                    .alignmentGuide(.bottom) { $0[.top] }
                            }
                        }
                    }
                }
                .overlay(alignment: .top) {
                    Rectangle().fill(GeunalColor.separator).frame(height: 1)
                }
                // The hint hangs below today's row, over the next one.
                .zIndex(hasToday ? 1 : 0)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Space.xs)
    }
}

/// "날짜를 누르면 추천이 떠요" under today's cell while the calendar is still empty (m1.md screen 4).
private struct FirstRunHint: View {
    var body: some View {
        Text("날짜를 누르면 추천이 떠요", bundle: .module)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(GeunalColor.background)
            .padding(.horizontal, Space.s)
            .padding(.vertical, Space.xs)
            .background(GeunalColor.ink, in: RoundedRectangle(cornerRadius: Radius.cell))
            .padding(.top, 6)
            .accessibilityIdentifier("month.hint")
    }
}

/// Text colors for days: Sunday and holidays red, Saturday blue (tone.md "날짜와 시간").
enum DayColors {
    static func weekday(_ weekday: Int) -> Color {
        switch weekday {
        case 1: GeunalColor.sunday
        case 7: GeunalColor.saturday
        default: GeunalColor.tertiaryText
        }
    }

    static func date(weekday: Int, holiday: Bool) -> Color {
        if holiday || weekday == 1 {
            return GeunalColor.sunday
        }
        return weekday == 7 ? GeunalColor.saturday : GeunalColor.ink
    }
}
