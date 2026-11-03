import Foundation
import GeunalCore
import Observation

public enum PermissionState: Equatable, Sendable {
    case notAsked
    case granted
    case denied
}

/// The OS permissions behind the permissions screen, so the model can be tested without system alerts.
@MainActor
public protocol SystemPermissions: AnyObject {
    func calendarState() -> PermissionState
    func requestCalendar() async -> PermissionState
    func deviceCalendarCount() -> Int
    func notificationState() async -> PermissionState
    func requestNotifications() async -> PermissionState
    func openSettings()
}

/// The permissions screen (m1.md screen 3): device calendars and notifications, plus the test statistics consent in
/// alpha builds. Every card can be refused; refusing only turns its button into "설정에서 켜기".
@MainActor
@Observable
public final class PermissionsModel {
    public private(set) var calendar: PermissionState = .notAsked
    public private(set) var calendarCount = 0
    public private(set) var notifications: PermissionState = .notAsked
    public private(set) var alphaStats = false
    public let showsAlphaStats: Bool

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let permissions: any SystemPermissions
    @ObservationIgnored private let notify: @MainActor (String) -> Void
    @ObservationIgnored private let importCalendars: @MainActor () async -> Int?

    /// `importCalendars` mirrors every calendar of the phone once access is given and says how many; `nil` leaves
    /// the count to the system's list.
    public init(
        core: any CoreProtocol, permissions: any SystemPermissions, channel: BuildChannel = .current,
        importCalendars: @escaping @MainActor () async -> Int? = { nil }, notify: @escaping @MainActor (String) -> Void
    ) {
        self.core = core
        self.permissions = permissions
        self.showsAlphaStats = channel == .alpha
        self.importCalendars = importCalendars
        self.notify = notify
    }

    /// "캘린더 열기" waits until both system permissions are answered either way; "나중에 할게요" never waits.
    public var canOpenCalendar: Bool { calendar != .notAsked && notifications != .notAsked }

    /// Also after Settings: access given there imports the calendars here, as a yes to the system's question does.
    public func refresh() async {
        let before = calendar
        calendar = permissions.calendarState()
        if calendar != .granted {
            calendarCount = 0
        } else if before != .granted {
            calendarCount = await imported()
        }
        notifications = await permissions.notificationState()
        if showsAlphaStats {
            alphaStats = (try? core.alphaStatsEnabled()) ?? false
        }
    }

    public func calendarTapped() async {
        switch calendar {
        case .notAsked:
            calendar = await permissions.requestCalendar()
            calendarCount = calendar == .granted ? await imported() : 0
        case .denied:
            permissions.openSettings()
        case .granted:
            break
        }
    }

    /// "가져왔어요 · 캘린더 3개" (m1.md screen 3): the calendars the mirror took.
    private func imported() async -> Int {
        if let count = await importCalendars() {
            return count
        }
        return permissions.deviceCalendarCount()
    }

    public func notificationsTapped() async {
        switch notifications {
        case .notAsked:
            notifications = await permissions.requestNotifications()
        case .denied:
            permissions.openSettings()
        case .granted:
            break
        }
    }

    /// Off by default and only on a tap (protocol §14). Turning it off again lives under 나.
    public func alphaStatsTapped() {
        guard showsAlphaStats, !alphaStats else { return }
        do {
            try core.setAlphaStats(enabled: true)
            alphaStats = true
        } catch {
            notify(Strings.settingFailed)
        }
    }
}
