import Foundation
import GeunalCore

/// Where tapping a local notification goes (m1.md §2.6): an event opens in the editor, a birthday the person in 사람·생일,
/// a leave suggestion the month view on its week.
public enum AlertRoute: Equatable, Sendable {
    case event(eventId: String, occurrence: String?)
    case person(personId: String)
    case week(day: String)

    /// The notification's user info: plain strings, so nothing but the route rides along.
    public var userInfo: [String: String] {
        switch self {
        case .event(let eventId, let occurrence):
            var info = ["route": "event", "event": eventId]
            info["occurrence"] = occurrence
            return info
        case .person(let personId):
            return ["route": "person", "person": personId]
        case .week(let day):
            return ["route": "week", "day": day]
        }
    }

    public init?(userInfo: [AnyHashable: Any]) {
        switch userInfo["route"] as? String {
        case "event":
            guard let eventId = userInfo["event"] as? String else { return nil }
            self = .event(eventId: eventId, occurrence: userInfo["occurrence"] as? String)
        case "person":
            guard let personId = userInfo["person"] as? String else { return nil }
            self = .person(personId: personId)
        case "week":
            guard let day = userInfo["day"] as? String else { return nil }
            self = .week(day: day)
        default:
            return nil
        }
    }
}

/// One local notification as the app schedules it.
public struct PlannedAlert: Equatable, Sendable {
    /// `alert.` and the core's id, which stays the same while the alert does.
    public let id: String
    public let fireAt: Date
    public let title: String
    public let body: String
    public let route: AlertRoute

    public init(id: String, fireAt: Date, title: String, body: String, route: AlertRoute) {
        self.id = id
        self.fireAt = fireAt
        self.title = title
        self.body = body
        self.route = route
    }

    static let prefix = "alert."
}

/// The system's notification center behind a seam, so the scheduling can be tested without it.
@MainActor
public protocol AlertCenter: AnyObject {
    /// Whether the person allows alerts at all.
    func allowsAlerts() async -> Bool
    /// What is scheduled now, as it was planned.
    func scheduled() async -> [PlannedAlert]
    func schedule(_ alert: PlannedAlert) async throws
    func cancel(_ ids: [String])
}

/// Keeps the nearest alerts scheduled (docs/parts/ios.md §5: iOS keeps 64 at most). The core says what fires when;
/// each refresh cancels what it no longer lists or words differently, and schedules what is missing, so an
/// unchanged calendar touches nothing.
@MainActor
public final class AlertScheduler {
    /// iOS keeps 64 pending local notifications per app.
    public nonisolated static let limit: UInt32 = 64

    private let core: any CoreProtocol
    private let center: any AlertCenter

    public init(core: any CoreProtocol, center: any AlertCenter) {
        self.core = core
        self.center = center
    }

    public func refresh() async {
        guard await center.allowsAlerts() else {
            // Turned off in the system settings: nothing is left to fire later.
            center.cancel(await center.scheduled().map(\.id))
            return
        }
        let core = self.core
        guard let planned = await Task.detached(operation: { try? Self.plan(core) }).value else { return }
        let current = await center.scheduled()
        let stale = current.filter { !planned.contains($0) }.map(\.id)
        if !stale.isEmpty {
            center.cancel(stale)
        }
        for alert in planned where !current.contains(alert) {
            try? await center.schedule(alert)
        }
    }

    /// The core's next alerts in the words of docs/design/tone.md, with a lunar birthday's date from its person.
    nonisolated static func plan(_ core: any CoreProtocol) throws -> [PlannedAlert] {
        let alerts = try core.scheduledAlerts(limit: limit)
        var lunar: [String: String] = [:]
        if alerts.contains(where: { if case .birthday = $0.target { true } else { false } }) {
            for person in try core.people() {
                if let birthday = person.birthday, case .lunar = birthday.calendar {
                    lunar[person.id] = PeopleText.birthday(birthday)
                }
            }
        }
        return alerts.map { alert in
            let route: AlertRoute
            var lunarBirthday: String?
            switch alert.target {
            case .event(let eventId, let occurrence):
                route = .event(eventId: eventId, occurrence: occurrence)
            case .birthday(let personId):
                route = .person(personId: personId)
                lunarBirthday = lunar[personId]
            case .leaveBridge(let bridge):
                route = .week(day: bridge.leaveDay)
            }
            let text = AlertContent.text(for: alert, lunarBirthday: lunarBirthday)
            return PlannedAlert(
                id: PlannedAlert.prefix + alert.id, fireAt: alert.fireAt, title: text.title, body: text.body,
                route: route)
        }
    }
}
