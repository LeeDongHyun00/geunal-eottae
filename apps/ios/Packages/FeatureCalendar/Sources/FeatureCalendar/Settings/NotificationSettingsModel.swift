import Foundation
import GeunalCore
import Observation

/// Whether the system lets the app alert, behind the settings screen so the model can be tested without it.
public enum AlertPermission: Equatable, Sendable {
    case notAsked
    case on
    case off
}

/// 알림 (m1.md screen 19): the app-wide defaults the calendars inherit, the next-up bar, birthday alerts and the
/// holiday and leave suggestions. Every alert rings on the device; nothing passes through the server. Each change is
/// saved at once.
@MainActor
@Observable
public final class NotificationSettingsModel {
    public private(set) var settings = NotificationSettings(
        eventAlert: 10, allDayAlert: 360, nextUpBar: true, birthdayAlerts: true, leaveSuggestions: true)
    public private(set) var permission: AlertPermission = .on
    public private(set) var loaded = false
    public private(set) var failed = false

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let permissionState: @MainActor () async -> AlertPermission
    /// The last write handed to the core. Each write waits for it, so an older snapshot never lands after a newer one.
    @ObservationIgnored private var lastWrite: Task<Void, Never>?
    /// What the core holds, for putting the switches back when a write is refused.
    @ObservationIgnored private var stored: NotificationSettings?

    public init(core: any CoreProtocol, permission: @escaping @MainActor () async -> AlertPermission) {
        self.core = core
        permissionState = permission
    }

    /// With the system's alerts off, the switches below still save but read dim (m1.md screen 19 "상태").
    public var systemOff: Bool { permission == .off }

    public static let eventChoices: [Int32?] = [nil, 0, 10, 30, 60, 1440]
    public static let allDayChoices: [Int32?] = [nil, -540, 360]

    public func load() async {
        let core = self.core
        if let read = try? await offMain({ try core.notificationSettings() }) {
            settings = read
            stored = read
        }
        permission = await permissionState()
        loaded = true
    }

    /// Reads the system permission again, for coming back from the Settings app.
    public func refreshPermission() async {
        permission = await permissionState()
    }

    public func setEventAlert(_ minutes: Int32?) async {
        var next = settings
        next.eventAlert = minutes
        await save(next)
    }

    public func setAllDayAlert(_ minutes: Int32?) async {
        var next = settings
        next.allDayAlert = minutes
        await save(next)
    }

    public func setNextUpBar(_ on: Bool) async {
        var next = settings
        next.nextUpBar = on
        await save(next)
    }

    public func setBirthdayAlerts(_ on: Bool) async {
        var next = settings
        next.birthdayAlerts = on
        await save(next)
    }

    public func setLeaveSuggestions(_ on: Bool) async {
        var next = settings
        next.leaveSuggestions = on
        await save(next)
    }

    /// "10분 전", "전날 18:00", "없음".
    public static func describe(_ minutes: Int32?, allDay: Bool) -> String {
        guard let minutes else { return AlertText.describe(.off, allDay: allDay) }
        return AlertText.describe(minutes: minutes, allDay: allDay)
    }

    /// Every change writes the whole snapshot, so writes go one after another in the order they were made.
    private func save(_ next: NotificationSettings) async {
        let fallback = stored ?? settings
        // Shown at once; put back if the core refuses.
        settings = next
        let core = self.core
        let before = lastWrite
        let write = Task { @MainActor [weak self] in
            await before?.value
            do {
                try await offMain { try core.setNotificationSettings(settings: next) }
                self?.stored = next
                self?.failed = false
            } catch {
                guard let self else { return }
                self.failed = true
                // A newer change already on screen keeps its own write; only the latest one puts things back.
                if self.settings == next {
                    self.settings = self.stored ?? fallback
                }
            }
        }
        lastWrite = write
        await write.value
    }
}
