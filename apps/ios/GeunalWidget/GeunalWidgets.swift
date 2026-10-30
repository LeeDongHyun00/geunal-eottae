import HomeWidget
import SwiftUI
import WidgetKit

/// The widget extension (docs/parts/ios.md §2): it reads the snapshot the app wrote in the App Group container and
/// never opens the core, which would not fit the extension's memory and time.
@main
struct GeunalWidgets: WidgetBundle {
    var body: some Widget {
        TodayWidget()
    }
}

/// 작은·중간 위젯 (m1.md screen 14), in light and dark.
struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "today", provider: SnapshotTimeline()) { entry in
            TodayWidgetView(entry: entry)
        }
        .configurationDisplayName(Text(verbatim: WidgetText.name))
        .description(Text(verbatim: WidgetText.summary))
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let content: WidgetContent
}

/// Today until the next Korean midnight, then the next day from the same snapshot: the app may not run in between,
/// and its tomorrow becomes today (rust-core.md §3). The app asks for a new timeline whenever it writes.
struct SnapshotTimeline: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: Date(), content: Self.sample(Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let now = Date()
        // The widget gallery shows the board's example rather than the person's day.
        completion(SnapshotEntry(date: now, content: context.isPreview ? Self.sample(now) : Self.content(at: now)))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let now = Date()
        let midnight = WidgetDays.nextMidnight(after: now)
        let source = Self.source()
        let entries = [now, midnight].map {
            SnapshotEntry(date: $0, content: WidgetContent.make(source, day: WidgetDays.day(of: $0)))
        }
        completion(Timeline(entries: entries, policy: .atEnd))
    }

    private static func content(at date: Date) -> WidgetContent {
        WidgetContent.make(source(), day: WidgetDays.day(of: date))
    }

    private static func source() -> WidgetContent.Source {
        guard let identifier = Bundle.main.object(forInfoDictionaryKey: "GeunalAppGroup") as? String,
            let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
        else { return .missing }
        return WidgetSnapshot.read(try? Data(contentsOf: WidgetFile.snapshot(in: container)))
    }

    /// boards/Widgets: 가족 · 저녁 today, 전공 수업 tomorrow.
    private static func sample(_ date: Date) -> WidgetContent {
        WidgetContent(
            day: WidgetDays.day(of: date),
            state: .events(
                today: [
                    WidgetSnapshot.Event(
                        title: "가족 · 저녁", color: "blossom", allDay: false, start: "18:00", end: "20:00")
                ],
                tomorrow: [
                    WidgetSnapshot.Event(title: "전공 수업", color: "sea", allDay: false, start: "10:00", end: "12:00")
                ]))
    }
}

struct TodayWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry

    var body: some View {
        Group {
            switch family {
            case .systemMedium:
                MediumWidgetView(content: entry.content)
            default:
                SmallWidgetView(content: entry.content)
            }
        }
        // A tap outside an event opens the month view on today (m1.md §2.6).
        .widgetURL(WidgetLink.today.url)
        .containerBackground(for: .widget) {
            WidgetColors.background
        }
    }
}
