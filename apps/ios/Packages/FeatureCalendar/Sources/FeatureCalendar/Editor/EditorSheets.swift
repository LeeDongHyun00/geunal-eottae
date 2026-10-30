import DesignSystem
import GeunalCore
import SwiftUI

/// "이 일정만 · 이후 모두" for an occurrence of a repeating event (m1.md screen 9 "동작"). Asked in a sheet, never an
/// alert (m1.md decision 4).
struct ScopeSheet: View {
    let title: Text
    let thisOnly: Text
    let following: Text
    let destructive: Bool
    let onChoose: (EditScope) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            title
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: Space.xs) {
                Button {
                    onChoose(.thisOnly)
                } label: {
                    thisOnly
                }
                .buttonStyle(.geunal(destructive ? .destructive : .secondary))
                .accessibilityIdentifier("scope.thisOnly")
                Button {
                    onChoose(.thisAndFollowing)
                } label: {
                    following
                }
                .buttonStyle(.geunal(destructive ? .destructive : .primary))
                .accessibilityIdentifier("scope.following")
                Button {
                    dismiss()
                } label: {
                    Text("취소", bundle: .module)
                }
                .buttonStyle(.geunal(.text))
            }
        }
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.top, Space.l)
        .sheetChrome(height: 300)
    }
}

/// The question before deleting an event that does not repeat.
struct DeleteSheet: View {
    let onAnswer: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            Text("일정을 삭제할까요?", bundle: .module)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .accessibilityAddTraits(.isHeader)
            Text("삭제한 일정은 되돌릴 수 없어요.", bundle: .module)
                .typeStyle(.secondary)
                .foregroundStyle(GeunalColor.secondaryText)
            VStack(spacing: Space.xs) {
                Button {
                    onAnswer(true)
                } label: {
                    Text("일정 삭제", bundle: .module)
                }
                .buttonStyle(.geunal(.destructive))
                .accessibilityIdentifier("delete.confirm")
                Button {
                    onAnswer(false)
                } label: {
                    Text("취소", bundle: .module)
                }
                .buttonStyle(.geunal(.text))
            }
        }
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.top, Space.l)
        .sheetChrome(height: 280)
    }
}

/// "직접 정하기": weekly on chosen weekdays, every one to four weeks.
struct CustomRepeatSheet: View {
    let startDay: String
    let onDone: (Recurrence?) -> Void
    @State private var weekdays: Set<Int>
    @State private var interval: Int

    init(recurrence: Recurrence?, startDay: String, onDone: @escaping (Recurrence?) -> Void) {
        self.startDay = startDay
        self.onDone = onDone
        let start = KoreanCalendar.weekday(of: startDay) ?? 1
        let days = recurrence?.frequency == .weekly ? Set((recurrence?.weekdays ?? []).map(\.number)) : []
        _weekdays = State(initialValue: days.isEmpty ? [start] : days)
        _interval = State(initialValue: recurrence?.frequency == .weekly ? Int(recurrence?.interval ?? 1) : 1)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            Text("직접 정하기", bundle: .module)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 6) {
                ForEach(1...7, id: \.self) { weekday in
                    let on = weekdays.contains(weekday)
                    Button {
                        if on {
                            if weekdays.count > 1 { weekdays.remove(weekday) }
                        } else {
                            weekdays.insert(weekday)
                        }
                    } label: {
                        Text(verbatim: DayText.weekdayName(weekday))
                            .typeStyle(.pillLabel)
                            .foregroundStyle(on ? GeunalColor.background : GeunalColor.ink)
                            .frame(maxWidth: .infinity, minHeight: Metrics.minHitTarget)
                            .background(on ? GeunalColor.ink : GeunalColor.fill, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(verbatim: DayText.weekdayLongName(weekday)))
                    .accessibilityAddTraits(on ? [.isSelected] : [])
                }
            }
            Stepper(value: $interval, in: 1...4) {
                Text(verbatim: summary)
                    .typeStyle(.body)
                    .foregroundStyle(GeunalColor.ink)
            }
            Button {
                onDone(recurrence)
            } label: {
                Text("완료", bundle: .module)
            }
            .buttonStyle(.geunal(.primary))
            .accessibilityIdentifier("customRepeat.done")
        }
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.top, Space.l)
        .sheetChrome(height: 340)
    }

    private var recurrence: Recurrence {
        Recurrence(
            frequency: .weekly, interval: UInt16(interval),
            weekdays: weekdays.sorted().compactMap(Weekday.init(number:)), until: nil, count: nil)
    }

    private var summary: String {
        RecurrenceText.describe(recurrence, startDay: startDay)
    }
}
