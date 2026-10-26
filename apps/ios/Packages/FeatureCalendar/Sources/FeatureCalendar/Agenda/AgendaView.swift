import DesignSystem
import GeunalCore
import SwiftUI

/// The list view (m1.md screen 8, boards/List): a heading per day, then a 52 pt row per event or birthday with the
/// calendar's dot, a caption ("학교 · 매주 월·수") and the time. It opens at today; the weeks before sit above it in
/// grey.
struct AgendaView: View {
    let model: AgendaModel
    let onDay: (AgendaDay) -> Void
    let onAddToday: () -> Void
    /// The top edge loads earlier weeks only after the list has moved to today, or it would load at once.
    @State private var settled = false

    /// Where the empty state sits in the list, so the list can open on it.
    private static let emptyID = "agenda.empty"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if model.canLoadEarlier {
                        Color.clear
                            .frame(height: 1)
                            .onAppear {
                                guard settled else { return }
                                Task { await model.loadEarlier() }
                            }
                    }
                    ForEach(model.days.filter { model.isPast($0) }, id: \.day) { day in
                        AgendaSection(day: day, today: model.today, past: true, onDay: onDay)
                            .id(day.day)
                    }
                    // Nothing in the next four weeks still keeps the weeks before and after within reach.
                    if model.hasLoaded && model.isEmpty {
                        EmptyAgenda(onAddToday: onAddToday)
                            .id(Self.emptyID)
                    }
                    ForEach(model.days.filter { !model.isPast($0) }, id: \.day) { day in
                        AgendaSection(day: day, today: model.today, past: false, onDay: onDay)
                            .id(day.day)
                    }
                    if model.canLoadMore {
                        ProgressView()
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .id(model.loadedThrough)
                            .onAppear { Task { await model.loadMore() } }
                    }
                }
                .padding(.horizontal, Space.l)
                .padding(.bottom, Space.l)
            }
            .scrollIndicators(.hidden)
            .onAppear { scrollToToday(proxy) }
            .onChange(of: model.hasLoaded) { scrollToToday(proxy) }
        }
    }

    /// Opens at the first day from today on, or at the empty state, once the first page is in.
    private func scrollToToday(_ proxy: ScrollViewProxy) {
        guard model.hasLoaded, !settled else { return }
        if model.isEmpty {
            proxy.scrollTo(Self.emptyID, anchor: .top)
        } else if let first = model.days.first(where: { !model.isPast($0) }) {
            proxy.scrollTo(first.day, anchor: .top)
        }
        // Let the move finish before the top edge may load the weeks before.
        Task {
            try? await Task.sleep(for: .milliseconds(300))
            settled = true
        }
    }
}

private struct EmptyAgenda: View {
    let onAddToday: () -> Void

    var body: some View {
        VStack(spacing: Space.m) {
            Text("앞으로 4주 동안 일정이 없어요", bundle: .module)
                .typeStyle(.body)
                .foregroundStyle(GeunalColor.secondaryText)
            Button(action: onAddToday) {
                Text("오늘 일정 넣기", bundle: .module)
            }
            .buttonStyle(.geunal(.pill, height: Metrics.Button.secondary))
            .accessibilityIdentifier("agenda.addToday")
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.xxxl)
    }
}

private struct AgendaSection: View {
    let day: AgendaDay
    let today: String
    let past: Bool
    let onDay: (AgendaDay) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                onDay(day)
            } label: {
                Text(verbatim: heading)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(headingColor)
                    .frame(maxWidth: .infinity, minHeight: 32, alignment: .bottomLeading)
                    .padding(.top, 10)
                    .padding(.bottom, 4)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isHeader)
            .accessibilityHint(Text("일정 넣기", bundle: .module))
            ForEach(day.birthdays, id: \.personId) { birthday in
                AgendaRow(
                    dot: .ring, title: String(localized: "\(birthday.name) 생일", bundle: .module),
                    caption: String(localized: "사람·생일", bundle: .module),
                    time: String(localized: "종일", bundle: .module), past: past)
            }
            ForEach(day.events, id: \.self) { event in
                AgendaRow(
                    dot: event.soft ? .soft(event.color) : .color(event.color), title: event.title,
                    caption: Self.caption(event), time: Self.time(event), past: past)
            }
        }
    }

    private var heading: String {
        let title = DayText.agendaHeading(day.day, today: today)
        guard !day.holidays.isEmpty else { return title }
        return ([title] + day.holidays.map(\.name)).joined(separator: " · ")
    }

    private var headingColor: Color {
        if past {
            return GeunalColor.tertiaryText
        }
        return day.day == today || !day.holidays.isEmpty ? GeunalColor.sunday : GeunalColor.secondaryText
    }

    /// "학교 · 매주 월·수", "운동 · 소프트 캘린더", "기기 캘린더 · 읽기 전용", or the calendar alone.
    static func caption(_ event: EventItem) -> String {
        if event.source == .device {
            return String(localized: "기기 캘린더 · 읽기 전용", bundle: .module)
        }
        if event.soft {
            return String(localized: "\(event.calendarName) · 소프트 캘린더", bundle: .module)
        }
        if let recurrence = event.recurrence {
            let rule = RecurrenceText.describe(recurrence, startDay: event.start.day)
            return String(localized: "\(event.calendarName) · \(rule)", bundle: .module)
        }
        return event.calendarName
    }

    static func time(_ event: EventItem) -> String {
        event.allDay ? String(localized: "종일", bundle: .module) : DayText.clock(event.start.minute)
    }
}

private struct AgendaRow: View {
    enum Dot {
        case color(Palette?)
        case soft(Palette?)
        /// A birthday: an ink ring (boards/List).
        case ring
    }

    let dot: Dot
    let title: String
    let caption: String
    let time: String
    let past: Bool

    var body: some View {
        HStack(spacing: Space.s) {
            dotView
                .frame(width: 10, height: 10)
                .opacity(past ? 0.4 : 1)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: title)
                    .typeStyle(.bodyStrong)
                    .foregroundStyle(past ? GeunalColor.tertiaryText : GeunalColor.ink)
                    .lineLimit(1)
                Text(verbatim: caption)
                    .typeStyle(.caption)
                    .foregroundStyle(past ? GeunalColor.tertiaryText : GeunalColor.secondaryText)
                    .lineLimit(1)
            }
            Spacer(minLength: Space.xs)
            Text(verbatim: time)
                .typeStyle(.secondary)
                .monospacedDigit()
                .foregroundStyle(past ? GeunalColor.tertiaryText : GeunalColor.secondaryText)
        }
        .frame(minHeight: 52)
        .overlay(alignment: .bottom) {
            Rectangle().fill(GeunalColor.fill).frame(height: 1)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var dotView: some View {
        switch dot {
        case .color(let palette):
            Circle().fill(CalendarColors.marker(palette))
        case .soft(let palette):
            EventFill(palette, soft: true).clipShape(Circle())
        case .ring:
            Circle().strokeBorder(GeunalColor.ink, lineWidth: 1.5)
        }
    }
}
