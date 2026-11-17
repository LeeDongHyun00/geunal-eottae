import Foundation
@preconcurrency import UserNotifications

/// The system's notification center (docs/parts/ios.md §5 "로컬 알림"). Each alert fires on a calendar trigger that
/// names one instant, and carries only its route.
@MainActor
public final class SystemAlertCenter: AlertCenter {
    public init() {}

    public func allowsAlerts() async -> Bool {
        switch await UNUserNotificationCenter.current().notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: true
        case .denied, .notDetermined: false
        @unknown default: false
        }
    }

    /// Only the app's own alerts: anything without the prefix belongs to something else.
    public func scheduled() async -> [PlannedAlert] {
        await UNUserNotificationCenter.current().pendingNotificationRequests().compactMap(Self.planned)
    }

    public func schedule(_ alert: PlannedAlert) async throws {
        let content = UNMutableNotificationContent()
        content.title = alert.title
        content.body = alert.body
        content.sound = .default
        content.userInfo = alert.route.userInfo
        let trigger = UNCalendarNotificationTrigger(dateMatching: Self.components(of: alert.fireAt), repeats: false)
        let request = UNNotificationRequest(identifier: alert.id, content: content, trigger: trigger)
        try await UNUserNotificationCenter.current().add(request)
    }

    public func cancel(_ ids: [String]) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }

    public func removeAll() {
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        center.removeAllDeliveredNotifications()
    }

    /// The instant as Gregorian components in GMT that carry their calendar and time zone, down to the second. A
    /// calendar trigger matches components, and components without a zone are a wall-clock time: after a flight or a
    /// daylight-saving change they would fire at another instant until the app next ran. With the zone the trigger
    /// means one instant wherever the phone is, and GMT has no hour that is skipped or repeated.
    public nonisolated static func components(of date: Date) -> DateComponents {
        var components = gmt.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        components.calendar = gmt
        components.timeZone = .gmt
        return components
    }

    /// The instant a trigger's components name. Components without a time zone come from a build that scheduled
    /// wall-clock times; they read as the distant past, which no planned alert equals, so the next refresh replaces
    /// them with instants.
    public nonisolated static func instant(of components: DateComponents) -> Date? {
        guard let timeZone = components.timeZone else { return Date.distantPast }
        var calendar = components.calendar ?? Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar.date(from: components)
    }

    private nonisolated static var gmt: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        return calendar
    }

    private nonisolated static func planned(_ request: UNNotificationRequest) -> PlannedAlert? {
        guard request.identifier.hasPrefix(PlannedAlert.prefix),
            let trigger = request.trigger as? UNCalendarNotificationTrigger,
            let fireAt = instant(of: trigger.dateComponents),
            let route = AlertRoute(userInfo: request.content.userInfo)
        else { return nil }
        return PlannedAlert(
            id: request.identifier, fireAt: fireAt, title: request.content.title, body: request.content.body,
            route: route)
    }
}
