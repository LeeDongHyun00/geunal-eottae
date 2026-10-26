@preconcurrency import EventKit
import UIKit
@preconcurrency import UserNotifications

/// The real permissions: EventKit full access (read only; the app never writes to device calendars) and notifications.
@MainActor
public final class DevicePermissions: SystemPermissions {
    public init() {}

    public func calendarState() -> PermissionState {
        switch EKEventStore.authorizationStatus(for: .event) {
        case .fullAccess: .granted
        case .notDetermined: .notAsked
        case .denied, .restricted, .writeOnly: .denied
        @unknown default: .denied
        }
    }

    public func requestCalendar() async -> PermissionState {
        // A store made after the answer sees the calendars; one made before keeps the old authorization.
        let store = EKEventStore()
        let granted = (try? await store.requestFullAccessToEvents()) ?? false
        return granted ? .granted : .denied
    }

    public func deviceCalendarCount() -> Int {
        EKEventStore().calendars(for: .event).count
    }

    public func notificationState() async -> PermissionState {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return .granted
        case .notDetermined: return .notAsked
        case .denied: return .denied
        @unknown default: return .denied
        }
    }

    public func requestNotifications() async -> PermissionState {
        let center = UNUserNotificationCenter.current()
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        return granted ? .granted : .denied
    }

    public func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}
