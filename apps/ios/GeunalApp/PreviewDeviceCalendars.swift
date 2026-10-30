#if DEBUG
    import FeatureCalendar
    import GeunalCore

    /// A made-up phone with two calendars that already said yes, for the UI tests of the device calendar mirror (m1.md
    /// screen 15). Debug builds only.
    @MainActor
    final class PreviewDeviceCalendars: DeviceCalendarSource {
        private static let phone = [
            DeviceCalendarInput(
                sourceId: "preview-work", name: "회사",
                events: [
                    event("preview-work-1", "주간 회의", day: "2026-11-02"),
                    event("preview-work-2", "면담", day: "2026-11-04"),
                ]),
            DeviceCalendarInput(
                sourceId: "preview-family", name: "가족", events: [event("preview-family-1", "제사", day: "2026-11-07")]),
        ]

        func access() -> DeviceCalendarAccess { .granted }
        func requestAccess() async -> DeviceCalendarAccess { .granted }

        func calendars() async -> [DeviceCalendarSummary] {
            Self.phone.map { DeviceCalendarSummary(id: $0.sourceId, name: $0.name, eventCount: $0.events.count) }
        }

        func read(_ ids: [String]) async -> [DeviceCalendarInput] {
            Self.phone.filter { ids.contains($0.sourceId) }
        }

        func openSettings() {}
        func observeChanges(_ action: @escaping @MainActor () -> Void) {}

        private static func event(_ id: String, _ title: String, day: String) -> DeviceEventInput {
            DeviceEventInput(
                deviceEventId: id, title: title, allDay: false, start: DayTime(day: day, minute: 9 * 60),
                end: DayTime(day: day, minute: 10 * 60))
        }
    }
#endif
