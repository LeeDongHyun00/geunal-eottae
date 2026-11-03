import FeatureCalendar
import MetricKit
import UIKit
@preconcurrency import UserNotifications

/// What the system hands the app outside its screens: notification taps, background time and crash reports. All of
/// them must be set up before the launch finishes.
final class AppDelegate: NSObject, UIApplicationDelegate {
    private let crashReports = CrashReports()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BackgroundWork.register()
        MXMetricManager.shared.add(crashReports)
        return true
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    /// An alert that comes due while the app is open still shows, as a banner.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    /// A tap opens what the alert is about (m1.md §2.6), once the tabs are there.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
            let route = AlertRoute(userInfo: response.notification.request.content.userInfo)
        else { return }
        await MainActor.run { AppModel.shared.open(route) }
    }
}

/// Crashes MetricKit reports on the launch after them, for the alpha statistics' `crashes` (protocol §14): only
/// `MXCrashDiagnostic`, counted by the Korean day of the report. MetricKit hands each report over once.
final class CrashReports: NSObject, MXMetricManagerSubscriber {
    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        var counts: [String: UInt32] = [:]
        for payload in payloads {
            let crashes = payload.crashDiagnostics?.count ?? 0
            if crashes > 0 {
                counts[KoreanCalendar.day(of: payload.timeStampEnd), default: 0] += UInt32(crashes)
            }
        }
        guard !counts.isEmpty else { return }
        Task { @MainActor in AppModel.shared.recordCrashes(counts) }
    }
}
