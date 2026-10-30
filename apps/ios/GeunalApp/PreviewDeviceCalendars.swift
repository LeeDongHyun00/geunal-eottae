#if DEBUG
    import FeatureCalendar
    import Foundation
    import GeunalCore

    /// A made-up phone with two calendars that already said yes, for the UI tests of the device calendar mirror (m1.md
    /// screen 15). Its events fall in the coming days, so the screens look the same whenever CI runs. Debug builds
    /// only.
    @MainActor
    final class PreviewDeviceCalendars: DeviceCalendarSource {
        private static var phone: [DeviceCalendarInput] {
            [
                DeviceCalendarInput(
                    sourceId: "preview-work", name: "Google · 회사",
                    events: [
                        event("preview-work-1", "주간 회의", inDays: 1, minute: 9 * 60),
                        event("preview-work-2", "면담", inDays: 3, minute: 14 * 60),
                    ]),
                DeviceCalendarInput(
                    sourceId: "preview-family", name: "iCloud · 가족",
                    events: [event("preview-family-1", "제사", inDays: 2, minute: 19 * 60)]),
            ]
        }

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

        private static func event(_ id: String, _ title: String, inDays days: Int, minute: UInt16) -> DeviceEventInput {
            let today = KoreanCalendar.day(of: Date())
            let day = KoreanCalendar.adding(days: days, to: today) ?? today
            return DeviceEventInput(
                deviceEventId: id, title: title, allDay: false, start: DayTime(day: day, minute: minute),
                end: DayTime(day: day, minute: minute + 60))
        }
    }
#endif
