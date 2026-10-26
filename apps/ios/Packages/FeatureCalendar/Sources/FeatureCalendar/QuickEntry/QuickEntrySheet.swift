import DesignSystem
import GeunalCore
import SwiftUI

/// The quick entry sheet (m1.md screen 5, boards/QuickEntry): the day as the title, five suggestion chips that save
/// at once, then a title and a range on the 0–24 strip, with the calendar and repeat chips and "저장" at the bottom.
struct QuickEntrySheet: View {
    /// The sheet keeps the model it opened with: the screen behind it redraws while it is up (a change reloads the
    /// views), and what the person typed must stay.
    @State private var model: QuickEntryModel
    private let actions: EventActions
    @Environment(\.dismiss) private var dismiss
    @FocusState private var titleFocused: Bool
    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 552

    init(model: QuickEntryModel, actions: EventActions) {
        _model = State(initialValue: model)
        self.actions = actions
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                suggestions
                Rectangle().fill(GeunalColor.separator).frame(height: 1)
                typed
            }
            .padding(.horizontal, Metrics.Sheet.Padding.side)
            .padding(.top, Space.l)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom) { bottomRow }
        .task { await model.load() }
        .sheetChrome(height: height)
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
            Button(action: save) {
                if model.needsTitle {
                    Text("제목을 적어 주세요", bundle: .module)
                } else {
                    Text("저장", bundle: .module)
                }
            }
            .buttonStyle(.geunal(.prominentPill, height: Metrics.Button.secondary))
            .disabled(!model.canSave)
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

    private func save() {
        guard let input = model.typedEvent() else { return }
        dismiss()
        Task { await actions.add(input, fromSuggestion: false) }
    }
}

/// A suggestion chip (components.md §3): white with a 1 pt border, the calendar's dot, the title in 15 · 600 and the
/// time in 15. It saves as soon as it is tapped.
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
