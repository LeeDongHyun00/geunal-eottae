import DesignSystem
import GeunalCore
import SwiftUI
import UIKit

/// The quick entry sheet (m1.md screen 5, boards/QuickEntry): the day as the title, five suggestion chips that save
/// at once, then a title and a range on the 0–24 strip, with the calendar and repeat chips and "저장" at the bottom.
struct QuickEntrySheet: View {
    /// The sheet keeps the model it opened with: the screen behind it redraws while it is up (a change reloads the
    /// views), and what the person typed must stay.
    @State private var model: QuickEntryModel
    /// Between "저장" and the core's answer; a second tap must not add the event twice.
    @State private var isSaving = false
    private let actions: EventActions
    /// "자세히": the editor with what is typed so far.
    private let onMore: (EventInput) -> Void
    /// A row of the day's events: that event's editor.
    private let onEvent: (EventItem) -> Void
    @Environment(\.dismiss) private var dismiss
    @FocusState private var titleFocused: Bool
    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 552
    @ScaledMetric(relativeTo: .body) private var eventRow: CGFloat = 44
    /// The content's own height and the bottom row's, measured, so the sheet ends where its content does.
    @State private var contentHeight: CGFloat?
    @State private var bottomRowHeight: CGFloat?

    init(
        model: QuickEntryModel, actions: EventActions, onMore: @escaping (EventInput) -> Void,
        onEvent: @escaping (EventItem) -> Void = { _ in }
    ) {
        _model = State(initialValue: model)
        self.actions = actions
        self.onMore = onMore
        self.onEvent = onEvent
    }

    /// The sheet is as tall as its content (m1.md screen 5 "높이"): 552 before the first layout, then what the
    /// header, the day's list, the chips and the typed part take. A fixed 552 plus the list left a gap above the
    /// bottom row when the day had no suggestions. Past 90% of the screen it stays there and the content scrolls.
    private var sheetHeight: CGFloat {
        guard let contentHeight, let bottomRowHeight else { return height }
        let window = UIApplication.shared.connectedScenes.lazy.compactMap { $0 as? UIWindowScene }
            .compactMap(\.keyWindow).first
        let measured = contentHeight + bottomRowHeight + (window?.safeAreaInsets.bottom ?? 0)
        guard let screen = window?.bounds.height else { return measured }
        return min(measured, screen * 0.9)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                dayEvents
                suggestions
                Rectangle().fill(GeunalColor.separator).frame(height: 1)
                typed
            }
            .padding(.horizontal, Metrics.Sheet.Padding.side)
            .padding(.top, Space.l)
            .padding(.bottom, Space.m)
            .onGeometryChange(for: CGFloat.self, of: \.size.height) { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom) {
            bottomRow.onGeometryChange(for: CGFloat.self, of: \.size.height) { bottomRowHeight = $0 }
        }
        .task { await model.load() }
        .sheetChrome(height: sheetHeight)
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: model.heading)
                    .font(.system(size: 22, weight: .semibold))
                    .tracking(-0.4)
                    .foregroundStyle(GeunalColor.ink)
                    .accessibilityAddTraits(.isHeader)
                if !model.notes.isEmpty {
                    Label {
                        Text(verbatim: model.notes.joined(separator: " · "))
                    } icon: {
                        Image(systemName: "gift")
                    }
                    .font(.system(size: 14))
                    .foregroundStyle(GeunalColor.secondaryText)
                }
            }
            Spacer()
            IconButton(systemImage: "xmark", label: Text("닫기", bundle: .module)) {
                dismiss()
            }
            .padding(.top, -6)
            .padding(.trailing, -12)
        }
    }

    /// "이날 일정 N개" and the day's events, three rows until "N개 더 보기" (m1.md decision 42).
    @ViewBuilder private var dayEvents: some View {
        if !model.events.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label {
                        Text("이날 일정 \(model.events.count)개", bundle: .module)
                    } icon: {
                        Image(systemName: "calendar")
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(GeunalColor.secondaryText)
                    .accessibilityAddTraits(.isHeader)
                    Spacer()
                    if model.hiddenEventCount > 0 {
                        Button {
                            withAnimation(.snappy) { model.showsAllEvents.toggle() }
                        } label: {
                            if model.showsAllEvents {
                                Text("접기", bundle: .module)
                            } else {
                                Text("\(model.hiddenEventCount)개 더 보기", bundle: .module)
                            }
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(GeunalColor.secondaryText)
                        .accessibilityIdentifier("quickEntry.moreEvents")
                    }
                }
                VStack(spacing: 0) {
                    ForEach(Array(model.listedEvents.enumerated()), id: \.offset) { index, event in
                        Button {
                            onEvent(event)
                        } label: {
                            DayEventRow(
                                event: event, time: model.time(of: event), height: eventRow,
                                separated: index < model.listedEvents.count - 1)
                        }
                        .buttonStyle(.pressable)
                        .accessibilityLabel(Text(verbatim: model.spokenTime(of: event)))
                        .accessibilityIdentifier("quickEntry.event.\(index)")
                    }
                }
                .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.card))
            }
            .accessibilityElement(children: .contain)
        }
    }

    @ViewBuilder private var suggestions: some View {
        if !model.suggestions.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Label {
                    Text("추천 · 누르면 바로 저장돼요", bundle: .module)
                } icon: {
                    Image(systemName: "sparkle")
                }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(GeunalColor.secondaryText)
                FlowLayout(spacing: Space.xs, rowSpacing: Space.xs) {
                    ForEach(model.suggestions.indices, id: \.self) { index in
                        let suggestion = model.suggestions[index]
                        SuggestionChip(suggestion: suggestion, color: model.color(of: suggestion)) {
                            dismiss()
                            Task { await actions.add(suggestion.event, fromSuggestion: true) }
                        }
                        .accessibilityIdentifier("quickEntry.chip.\(index)")
                    }
                }
            }
        }
    }

    private var typed: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            Text("직접 입력", bundle: .module)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(GeunalColor.secondaryText)
            TextField(text: $model.title) {
                Text("제목", bundle: .module)
            }
            .font(.system(size: 17))
            .focused($titleFocused)
            .submitLabel(.done)
            .onSubmit(save)
            .padding(.horizontal, Space.m)
            .frame(height: 48)
            .background(GeunalColor.groupedBackground, in: RoundedRectangle(cornerRadius: Radius.input))
            .accessibilityIdentifier("quickEntry.title")
            TimeStrip(range: $model.range)
        }
    }

    private var bottomRow: some View {
        HStack(spacing: Space.xs) {
            calendarMenu
            repeatMenu
            Spacer(minLength: 0)
            Button {
                onMore(model.draft())
            } label: {
                Text("자세히", bundle: .module)
            }
            .buttonStyle(.geunal(.text, height: Metrics.Button.secondary))
            .accessibilityIdentifier("quickEntry.more")
            Button(action: save) {
                if model.needsTitle {
                    Text("제목을 적어 주세요", bundle: .module)
                } else {
                    Text("저장", bundle: .module)
                }
            }
            .buttonStyle(.geunal(.prominentPill, height: Metrics.Button.secondary))
            .disabled(!model.canSave || isSaving)
            .accessibilityIdentifier("quickEntry.save")
        }
        .padding(.horizontal, Metrics.Sheet.Padding.side)
        .padding(.vertical, Space.xs)
        .background(GeunalColor.background)
    }

    private var calendarMenu: some View {
        Menu {
            Picker(selection: $model.calendarId) {
                ForEach(model.calendars, id: \.id) { calendar in
                    Text(verbatim: calendar.name).tag(Optional(calendar.id))
                }
            } label: {
                Text("캘린더", bundle: .module)
            }
        } label: {
            HStack(spacing: 6) {
                Circle()
                    .fill(CalendarColors.marker(model.selectedCalendar?.color))
                    .frame(width: Metrics.Chip.dot, height: Metrics.Chip.dot)
                Text(verbatim: model.selectedCalendar?.name ?? "")
            }
            .typeStyle(.pillLabel)
            .foregroundStyle(GeunalColor.ink)
            .padding(.horizontal, 14)
            .frame(minHeight: Metrics.Button.secondary)
            .background(GeunalColor.fill, in: Capsule())
        }
        .accessibilityLabel(Text("캘린더", bundle: .module))
        .accessibilityValue(Text(verbatim: model.selectedCalendar?.name ?? ""))
        .accessibilityIdentifier("quickEntry.calendar")
    }

    private var repeatMenu: some View {
        Menu {
            Picker(selection: $model.repeatChoice) {
                ForEach(QuickEntryModel.Repeat.allCases, id: \.self) { choice in
                    Text(verbatim: model.label(of: choice)).tag(choice)
                }
            } label: {
                Text("반복", bundle: .module)
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "repeat")
                    .font(.system(size: 14, weight: .semibold))
                Text(verbatim: model.label(of: model.repeatChoice))
            }
            .typeStyle(.pillLabel)
            .foregroundStyle(GeunalColor.ink)
            .padding(.horizontal, 14)
            .frame(minHeight: Metrics.Button.secondary)
            .background(GeunalColor.fill, in: Capsule())
        }
        .accessibilityLabel(Text("반복", bundle: .module))
        .accessibilityValue(Text(verbatim: model.label(of: model.repeatChoice)))
        .accessibilityIdentifier("quickEntry.repeat")
    }

    /// The sheet stays up with what was typed when the core refuses, so the toast's "다시 시도해 주세요" can be done.
    private func save() {
        guard !isSaving, let input = model.typedEvent() else { return }
        isSaving = true
        Task {
            if await actions.add(input, fromSuggestion: false) {
                dismiss()
            }
            isSaving = false
        }
    }
}

/// A suggestion chip (components.md §3): white with a 1 pt border, the calendar's dot, the title in 15 · 600 and the
/// time in 15. It saves as soon as it is tapped.
/// One of the day's events: the calendar's dot, the title and the time on this day (m1.md screen 5 "상태").
private struct DayEventRow: View {
    let event: EventItem
    let time: String
    let height: CGFloat
    let separated: Bool

    var body: some View {
        HStack(spacing: Space.s) {
            Circle()
                .fill(CalendarColors.marker(event.color))
                .frame(width: 8, height: 8)
            Text(verbatim: event.title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(GeunalColor.ink)
                .lineLimit(1)
            Spacer(minLength: Space.xs)
            Text(verbatim: time)
                .font(.system(size: 15))
                .monospacedDigit()
                .foregroundStyle(GeunalColor.secondaryText)
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: height)
        .overlay(alignment: .bottom) {
            if separated {
                Rectangle().fill(GeunalColor.separator).frame(height: 1)
            }
        }
        .contentShape(Rectangle())
    }
}

private struct SuggestionChip: View {
    let suggestion: Suggestion
    let color: Palette?
    let action: () -> Void

    var body: some View {
        let event = suggestion.event
        Button(action: action) {
            HStack(spacing: Space.xs) {
                Circle()
                    .fill(CalendarColors.marker(color))
                    .frame(width: Metrics.Chip.dot, height: Metrics.Chip.dot)
                Text(verbatim: event.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(GeunalColor.ink)
                if !event.allDay {
                    Text(verbatim: DayText.chipTime(start: event.start.minute, end: Self.endMinute(event)))
                        .font(.system(size: 15))
                        .monospacedDigit()
                        .foregroundStyle(GeunalColor.secondaryText)
                }
            }
            .padding(.horizontal, Space.m)
            .frame(minHeight: Metrics.Chip.suggestion)
            .background(GeunalColor.background, in: Capsule())
            .overlay(Capsule().strokeBorder(GeunalColor.separator, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.pressable)
        .accessibilityHint(Text("누르면 바로 저장돼요", bundle: .module))
    }

    /// An end on the next day at midnight reads as 24 only for a range ending at midnight; past midnight it is the
    /// next day's hour ("22–02").
    static func endMinute(_ event: EventInput) -> UInt16 {
        event.end.day != event.start.day && event.end.minute == 0 ? 1440 : event.end.minute
    }
}
