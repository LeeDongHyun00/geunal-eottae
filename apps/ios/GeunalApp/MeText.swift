import FeatureCalendar
import Foundation
import SwiftUI
import UIKit
import UserNotifications

/// The 나 screens' dates and system hooks, kept in the app so the account package needs neither the calendar's date
/// style nor UIKit's settings URL.
enum MeText {
    /// "오늘 03:12", "어제 03:10", "10월 23일 03:11", and the year in front in another year.
    static func time(_ date: Date) -> String {
        let today = KoreanCalendar.day(of: Date())
        let day = KoreanCalendar.day(of: date)
        let parts = KoreanCalendar.calendar.dateComponents([.hour, .minute], from: date)
        let clock = DayText.clock(UInt16((parts.hour ?? 0) * 60 + (parts.minute ?? 0)))
        switch KoreanCalendar.days(from: day, to: today) {
        case 0: return String(localized: "오늘 \(clock)")
        case 1: return String(localized: "어제 \(clock)")
        default: break
        }
        guard let parsed = KoreanCalendar.parse(day), let now = KoreanCalendar.parse(today) else { return clock }
        if parsed.year != now.year {
            let year = String(parsed.year)
            return String(localized: "\(year)년 \(parsed.month)월 \(parsed.day)일 \(clock)")
        }
        return String(localized: "\(parsed.month)월 \(parsed.day)일 \(clock)")
    }

    /// Screen 13's line under the warning.
    @MainActor
    static func backupLine(_ last: Date?) -> Text {
        guard let last else {
            return Text("암호화 백업 · 최근 3개 보관 · 아직 백업 없음\n사진·파일은 백업에 들어가지 않고, 보관 기간 안이면 다시 받아요.")
        }
        let time = Self.time(last)
        return Text("암호화 백업 · 최근 3개 보관 · 마지막 백업 \(time)\n사진·파일은 백업에 들어가지 않고, 보관 기간 안이면 다시 받아요.")
    }

    static func alertPermission() async -> AlertPermission {
        switch await UNUserNotificationCenter.current().notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: .on
        case .notDetermined: .notAsked
        case .denied: .off
        @unknown default: .off
        }
    }

    @MainActor
    static func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}
