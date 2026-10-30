import DesignSystem
import GeunalCore
import SwiftUI

/// The week view (m1.md screen 7, boards/Week): the days with their dates, the all-day row, and a 0–24 time axis that
/// opens at 8 o'clock. Pages swipe sideways a week at a time.
struct WeekView: View {
    @Bindable var model: WeekModel
    /// A tap on a date in the header row.
    let onDay: (WeekDay) -> Void
    /// A long press on an empty hour: the day and the hour.
    let onSlot: (WeekDay, Int) -> Void
    /// A tap on a block or an all-day bar opens the editor.
    let onEvent: (EventItem) -> Void

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(model.weeks, id: \.self) { week in
                    WeekPage(
                        days: model.pages[week], today: model.today, nowMinute: model.nowMinute(on: week),
                        onDay: onDay, onSlot: onSlot, onEvent: onEvent
                    )
                    .containerRelativeFrame(.horizontal)
                    .task { await model.load(week) }
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $model.visibleWeek)
        .scrollIndicators(.hidden)
    }
}

/// The time axis gutter on the left (boards/Week: 40).
private let gutter: CGFloat = 40

private struct WeekPage: View {
    let days: [WeekDay]?
    let today: String
    let nowMinute: Int?
    let onDay: (WeekDay) -> Void
    let onSlot: (WeekDay, Int) -> Void
    let onEvent: (EventItem) -> Void
    private let hourHeight = Metrics.Grid.weekHour

    var body: some View {
        VStack(spacing: 0) {
            if let days {
                DateRow(days: days, today: today, onDay: onDay)
                AllDayRow(days: days, onEvent: onEvent)
                ScrollViewReader { proxy in
                    ScrollView(.vertical) {
                        ZStack(alignment: .topLeading) {
                            HourLines(hourHeight: hourHeight)
                            GeometryReader { geometry in
                                let column = (geometry.size.width - gutter) / 7
                                SlotTargets(days: days, column: column, hourHeight: hourHeight, onSlot: onSlot)
                                ForEach(days.indices, id: \.self) { index in
                                    ForEach(days[index].timed, id: \.self) { block in
                                        let rect = Self.rect(
                                            of: block, day: index, column: column, hourHeight: hourHeight)
                                        Button {
                                            onEvent(block.event)
                                        } label: {
                                            TimedBlockView(block: block, hourHeight: hourHeight)
                                        }
                                        .buttonStyle(.pressable)
                                        .frame(width: rect.width, height: rect.height)
                                        .offset(x: rect.minX, y: rect.minY)
                                    }
                                }
                                if let nowMinute, let index = days.firstIndex(where: { $0.day == today }) {
                                    NowLine(width: geometry.size.width - gutter, dotX: CGFloat(index) * column)
                                        .offset(x: gutter, y: CGFloat(nowMinute) / 60 * hourHeight - 4)
                                }
                            }
                        }
                        .frame(height: 24 * hourHeight)
                        .padding(.bottom, Space.s)
                    }
                    .scrollIndicators(.hidden)
                    .onAppear { proxy.scrollTo(8, anchor: .top) }
                }
            } else {
                Spacer()
            }
        }
    }

    /// Where a block sits: its day's column split by the side-by-side columns the core gave it, from its start minute,
    /// never shorter than one line of title.
    static func rect(of block: TimedBlock, day: Int, column: CGFloat, hourHeight: CGFloat) -> CGRect {
        let columns = CGFloat(max(Int(block.columns), 1))
        let width = column / columns
        let x = gutter + CGFloat(day) * column + CGFloat(Int(block.column)) * width + 1
        let y = CGFloat(Int(block.startMinute)) / 60 * hourHeight + 1
        let minutes = CGFloat(Int(block.endMinute) - Int(block.startMinute))
        let height = max(minutes / 60 * hourHeight - 2, 18)
        return CGRect(x: x, y: y, width: width - 2, height: height)
    }
}

private struct DateRow: View {
    let days: [WeekDay]
    let today: String
    let onDay: (WeekDay) -> Void

    var body: some View {
        HStack(spacing: 0) {
            Color.clear.frame(width: gutter)
            ForEach(days, id: \.day) { day in
                Button {
                    onDay(day)
                } label: {
                    VStack(spacing: 4) {
                        Text(verbatim: DayText.weekdayName(day.weekday.number))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(
                                day.holidays.isEmpty ? DayColors.weekday(day.weekday.number) : GeunalColor.sunday)
                        let isToday = day.day == today
                        Text(verbatim: String(KoreanCalendar.parse(day.day)?.day ?? 0))
                            .font(.system(size: 17, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(
                                isToday
                                    ? GeunalColor.background
                                    : DayColors.date(weekday: day.weekday.number, holiday: !day.holidays.isEmpty)
                            )
                            .frame(width: 32, height: 32)
                            .background {
                                if isToday {
                                    Circle().fill(GeunalColor.ink)
                                }
                            }
                    }
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.pressable)
                .accessibilityLabel(Text(verbatim: Self.label(day, today: today)))
                .accessibilityIdentifier("week.day.\(day.day)")
            }
        }
        .padding(.top, Space.xxs)
    }

    static func label(_ day: WeekDay, today: String) -> String {
        var parts = [DayText.title(day.day, today: today)]
        if day.day == today {
            parts.append(String(localized: "오늘", bundle: .module))
        }
        parts += day.holidays.map(\.name)
        let count = day.allDay.count + day.timed.count + Int(day.hidden)
        if count > 0 {
            parts.append(String(localized: "일정 \(count)개", bundle: .module))
        }
        parts += day.birthdays.map { String(localized: "\($0.name) 생일", bundle: .module) }
        return parts.joined(separator: ", ")
    }
}

/// Birthdays and all-day events above the axis, with the "+N" of timed events that did not fit three columns.
private struct AllDayRow: View {
    let days: [WeekDay]
    let onEvent: (EventItem) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Text("종일", bundle: .module)
                .font(.system(size: 11))
                .foregroundStyle(GeunalColor.tertiaryText)
                .frame(width: gutter, alignment: .leading)
                .padding(.leading, Space.xs)
                .padding(.top, 5)
            ForEach(days, id: \.day) { day in
                VStack(spacing: 2) {
                    ForEach(day.birthdays, id: \.personId) { birthday in
                        BirthdayLine(name: birthday.name)
                    }
                    ForEach(day.allDay, id: \.self) { event in
                        Button {
                            onEvent(event)
                        } label: {
                            EventBar(event: event)
                        }
                        .buttonStyle(.pressable)
                    }
                    if day.hidden > 0 {
                        Text(verbatim: "+\(day.hidden)")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(GeunalColor.secondaryText)
                    }
                }
                .padding(.horizontal, 1)
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 4)
        .frame(minHeight: 26)
        .overlay(alignment: .top) { Rectangle().fill(GeunalColor.separator).frame(height: 1) }
        .overlay(alignment: .bottom) { Rectangle().fill(GeunalColor.separator).frame(height: 1) }
    }
}

private struct HourLines: View {
    let hourHeight: CGFloat

    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<24, id: \.self) { hour in
                HStack(alignment: .top, spacing: 0) {
                    Text(verbatim: String(hour))
                        .font(.system(size: 11))
                        .monospacedDigit()
                        .foregroundStyle(GeunalColor.tertiaryText)
                        .frame(width: gutter - Space.xs, alignment: .leading)
                        .padding(.leading, Space.xs)
                        .offset(y: -6)
                    Rectangle()
                        .fill(GeunalColor.fill)
                        .frame(height: 1)
                }
                .frame(height: hourHeight, alignment: .top)
                .id(hour)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Long-pressing an empty hour opens the quick entry at that hour (m1.md screen 7). Blocks lie on top, so a press on
/// an event never lands here.
private struct SlotTargets: View {
    let days: [WeekDay]
    let column: CGFloat
    let hourHeight: CGFloat
    let onSlot: (WeekDay, Int) -> Void

    var body: some View {
        ForEach(days.indices, id: \.self) { index in
            ForEach(0..<24, id: \.self) { hour in
                Color.clear
                    .contentShape(Rectangle())
                    .frame(width: column, height: hourHeight)
                    .offset(x: gutter + CGFloat(index) * column, y: CGFloat(hour) * hourHeight)
                    .onLongPressGesture(minimumDuration: 0.4) {
                        onSlot(days[index], hour)
                    }
            }
        }
        .accessibilityHidden(true)
    }
}

/// An event block (components.md §8): radius 8, tint 2 with a 2 pt calendar-color edge on the left, the title in
/// 12 · 600 and the start time in 11 when there is room.
private struct TimedBlockView: View {
    let block: TimedBlock
    let hourHeight: CGFloat
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let event = block.event
        let text = CalendarColors.bar(event.color, scheme: scheme).text
        VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: event.title)
                .font(.system(size: 12, weight: .semibold))
                .tracking(-0.3)
                .lineLimit(3)
            if CGFloat(Int(block.endMinute) - Int(block.startMinute)) / 60 * hourHeight >= 44 {
                Text(verbatim: DayText.clock(event.allDay ? 0 : event.start.minute))
                    .font(.system(size: 11))
                    .monospacedDigit()
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(text)
        .padding(.leading, 6)
        .padding(.trailing, 3)
        .padding(.vertical, 3)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(EventFill(event.color, soft: event.soft))
        .overlay(alignment: .leading) {
            Rectangle().fill(CalendarColors.marker(event.color)).frame(width: 2)
        }
        .clipShape(RoundedRectangle(cornerRadius: Radius.cell))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: Self.label(block)))
    }

    static func label(_ block: TimedBlock) -> String {
        let range = DayText.range(start: block.startMinute, end: block.endMinute)
        return "\(block.event.title), \(range)"
    }
}

/// The red line at the current time with a dot where today's column starts (m1.md screen 7).
private struct NowLine: View {
    let width: CGFloat
    let dotX: CGFloat

    var body: some View {
        ZStack(alignment: .leading) {
            Rectangle()
                .fill(GeunalColor.sunday)
                .frame(width: width, height: 1.5)
            Circle()
                .fill(GeunalColor.sunday)
                .frame(width: 8, height: 8)
                .offset(x: dotX - 4)
        }
        .frame(height: 8)
        .accessibilityHidden(true)
    }
}
