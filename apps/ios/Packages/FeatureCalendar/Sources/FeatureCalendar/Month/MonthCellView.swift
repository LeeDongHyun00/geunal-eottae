import DesignSystem
import GeunalCore
import SwiftUI

/// One day of the month grid (components.md §8): the date (today in an ink circle), the holiday in red, birthdays, and
/// up to three lines of event bars with "+N". The whole cell is one button that VoiceOver reads as a sentence
/// ("10월 30일 금요일, 일정 2개, 민수 생일"); the bars are not read one by one (m1.md screen 4 "접근성").
struct MonthCellView: View {
    let cell: DayCell
    let today: String
    let height: CGFloat
    let action: () -> Void
    let onEvent: (EventItem) -> Void
    let onLeave: () -> Void

    private var isToday: Bool { cell.day == today }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                DateMark(cell: cell, isToday: isToday)
                if let holiday = cell.holidays.first {
                    Text(verbatim: holiday.name)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(GeunalColor.sunday)
                        .lineLimit(1)
                }
                if cell.leaveBridge != nil {
                    // The mark and the bars take their own taps before the cell's quick entry.
                    LeaveMark()
                        .highPriorityGesture(TapGesture().onEnded { onLeave() })
                }
                ForEach(lines.indices, id: \.self) { index in
                    switch lines[index] {
                    case .birthday(let birthday):
                        BirthdayLine(name: birthday.name)
                    case .event(let event):
                        EventBar(event: event)
                            .highPriorityGesture(TapGesture().onEnded { onEvent(event) })
                    case .more(let count):
                        Text(verbatim: "+\(count)")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(GeunalColor.secondaryText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.leading, 2)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 4)
            .padding(.horizontal, 1)
            .frame(maxWidth: .infinity, minHeight: height, maxHeight: height, alignment: .top)
            .clipped()
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(Text(verbatim: MonthCellView.label(cell, today: today)))
        // VoiceOver reads the day as one button; its events open from the actions rotor.
        .accessibilityActions {
            ForEach(cell.events, id: \.self) { event in
                Button {
                    onEvent(event)
                } label: {
                    Text(verbatim: event.title)
                }
            }
            if cell.leaveBridge != nil {
                Button(action: onLeave) {
                    Text("연차 넣기", bundle: .module)
                }
            }
        }
        .accessibilityIdentifier("month.day.\(cell.day)")
    }

    enum Line {
        case birthday(BirthdayItem)
        case event(EventItem)
        case more(Int)
    }

    /// Birthdays then events, three lines at most and two under a holiday; when they do not fit the last line becomes
    /// "+N" for what is left.
    var lines: [Line] {
        let items = cell.birthdays.map(Line.birthday) + cell.events.map(Line.event)
        let room = cell.holidays.isEmpty && cell.leaveBridge == nil ? 3 : 2
        guard items.count > room else { return items }
        return Array(items.prefix(room - 1)) + [.more(items.count - (room - 1))]
    }

    static func label(_ cell: DayCell, today: String) -> String {
        var parts = [DayText.title(cell.day, today: today)]
        if cell.day == today {
            parts.append(String(localized: "오늘", bundle: .module))
        }
        parts += cell.holidays.map(\.name)
        if !cell.events.isEmpty {
            parts.append(String(localized: "일정 \(cell.events.count)개", bundle: .module))
        }
        parts += cell.birthdays.map { String(localized: "\($0.name) 생일", bundle: .module) }
        if let bridge = cell.leaveBridge {
            parts.append(String(localized: "연차 1일로 \(Int(bridge.daysOff))일 연휴", bundle: .module))
        }
        return parts.joined(separator: ", ")
    }
}

private struct DateMark: View {
    let cell: DayCell
    let isToday: Bool

    var body: some View {
        let day = KoreanCalendar.parse(cell.day)?.day ?? 0
        Text(verbatim: String(day))
            .font(.system(size: 13, weight: cell.inMonth || isToday ? .semibold : .regular))
            .monospacedDigit()
            .foregroundStyle(foreground)
            .frame(width: 24, height: 24)
            .background {
                if isToday {
                    Circle().fill(GeunalColor.ink)
                }
            }
    }

    private var foreground: Color {
        if isToday {
            return GeunalColor.background
        }
        if !cell.inMonth {
            return GeunalColor.tertiaryText
        }
        return DayColors.date(weekday: cell.weekday.number, holiday: !cell.holidays.isEmpty)
    }
}

/// The leave day of a long weekend (rust-core.md §6 "공휴일과 연차 다리"), a dashed "연차" mark.
private struct LeaveMark: View {
    var body: some View {
        Text("연차", bundle: .module)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(GeunalColor.sunday)
            .padding(.horizontal, 4)
            .frame(height: 14)
            .overlay(Capsule().strokeBorder(GeunalColor.sunday, style: StrokeStyle(lineWidth: 1, dash: [2, 2])))
    }
}

struct BirthdayLine: View {
    let name: String

    var body: some View {
        HStack(spacing: 2) {
            Image(systemName: "gift")
                .font(.system(size: 9, weight: .semibold))
            Text(verbatim: name)
                .font(.system(size: 11, weight: .semibold))
                .tracking(-0.3)
                .lineLimit(1)
        }
        .foregroundStyle(GeunalColor.ink)
        .padding(.horizontal, 2)
        .frame(maxWidth: .infinity, minHeight: Metrics.Grid.eventBar, alignment: .leading)
    }
}

/// An event bar (components.md §8): 16 high, radius 4, the calendar's tint 2 with ink text in 11.
struct EventBar: View {
    let event: EventItem
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(verbatim: event.title)
            .font(.system(size: 11, weight: .semibold))
            .tracking(-0.3)
            .lineLimit(1)
            .foregroundStyle(CalendarColors.bar(event.color, scheme: scheme).text)
            .padding(.horizontal, 2)
            .frame(
                maxWidth: .infinity, minHeight: Metrics.Grid.eventBar, maxHeight: Metrics.Grid.eventBar,
                alignment: .leading
            )
            .background(EventFill(event.color, soft: event.soft))
            .clipShape(RoundedRectangle(cornerRadius: Radius.strip))
    }
}
