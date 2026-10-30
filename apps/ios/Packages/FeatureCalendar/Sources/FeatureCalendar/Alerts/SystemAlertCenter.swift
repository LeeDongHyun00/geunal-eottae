import Foundation
@preconcurrency import UserNotifications

/// The system's notification center (docs/parts/ios.md §5 "로컬 알림"). Each alert fires on a calendar trigger and
/// carries only its route.
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

    /// The instant as the phone's own calendar and time zone name it, down to the second, which is what a calendar
    /// trigger matches. A phone that moves to another time zone reads its triggers back as other instants, and the
    /// next refresh schedules them again.
    nonisolated static func components(of date: Date) -> DateComponents {
        Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
    }

    private nonisolated static func planned(_ request: UNNotificationRequest) -> PlannedAlert? {
        guard request.identifier.hasPrefix(PlannedAlert.prefix),
            let trigger = request.trigger as? UNCalendarNotificationTrigger,
            let fireAt = Calendar.current.date(from: trigger.dateComponents),
            let route = AlertRoute(userInfo: request.content.userInfo)
        else { return nil }
        return PlannedAlert(
            id: request.identifier, fireAt: fireAt, title: request.content.title, body: request.content.body,
            route: route)
    }
}
