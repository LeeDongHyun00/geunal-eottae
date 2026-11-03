import Foundation
import GeunalCore
import Observation

/// 나 (m1.md screen 12): the account card and the counts and states its rows show. The calendar works without an
/// account, so every row stays usable when there is none; only the security and backup rows depend on one.
@MainActor
@Observable
public final class MeModel {
    public enum Account: Equatable, Sendable {
        case loading
        /// No account on this device yet, or a guest still waiting for the first connection.
        case none
        case guest(AccountView)
        case provider(AccountView)
        /// Another device's sign-in signed this one out (screen 23); the calendar goes on.
        case signedOut(Revocation)
        /// The core ended the session: the server refused its refresh token (m1.md §4 "로그인이 끝났을 때", decision
        /// 43). The device still holds the account, and signing in again goes on from here.
        case sessionEnded(AccountKind)
    }

    public private(set) var account: Account = .loading
    /// What the app knows of an ended session, which the core no longer reports once the session is gone.
    public var sessionEnd: AccountKind?
    public private(set) var summary: MeSummary?
    public private(set) var alphaStats = false
    public let showsAlphaStats: Bool

    @ObservationIgnored private let core: any CoreProtocol

    public init(core: any CoreProtocol, channel: BuildChannel = .current) {
        self.core = core
        showsAlphaStats = channel == .alpha
    }

    /// Every way to the twelve words (나 › 복구 코드, the calendar's banner, the backup and device-change screens) asks
    /// the OS first. Cancelling or failing opens nothing; a device without a screen lock passes and the screen recommends
    /// one (m1.md decision 26, docs/qa/m1.md D11).
    public static func mayOpenRecoveryCode(owner: any DeviceOwnerCheck) async -> Bool {
        await owner.confirm(.recoveryCode)
    }

    /// The security and backup rows read dim without an account to secure.
    public var hasAccount: Bool {
        switch account {
        case .guest, .provider: true
        default: false
        }
    }

    public var isGuest: Bool {
        if case .guest = account { return true }
        return false
    }

    public func load() async {
        let core = self.core
        let read = try? await offMain { (try core.meSummary(), try core.revocation()) }
        summary = read?.0
        if let revocation = read?.1 {
            account = .signedOut(revocation)
        } else if let view = read?.0.account {
            account = view.providers.isEmpty ? .guest(view) : .provider(view)
        } else if let sessionEnd {
            account = .sessionEnded(sessionEnd)
        } else {
            account = .none
        }
        if showsAlphaStats {
            alphaStats = (try? await offMain { try core.alphaStatsEnabled() }) ?? false
        }
    }

    /// The alpha-build switch for the daily counts; it reads back what the core kept.
    public func setAlphaStats(_ on: Bool) async {
        let core = self.core
        try? await offMain { try core.setAlphaStats(enabled: on) }
        alphaStats = (try? await offMain { try core.alphaStatsEnabled() }) ?? false
    }
}
