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
    }

    public private(set) var account: Account = .loading
    public private(set) var summary: MeSummary?
    public private(set) var alphaStats = false
    public let showsAlphaStats: Bool

    @ObservationIgnored private let core: any CoreProtocol

    public init(core: any CoreProtocol, channel: BuildChannel = .current) {
        self.core = core
        showsAlphaStats = channel == .alpha
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
