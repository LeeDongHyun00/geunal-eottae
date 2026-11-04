import Foundation
import GeunalCore
import Observation

/// "이 기기에 다른 계정의 일정이 남아 있어요" (m1.md screen 24) after an account is decided without a restore: a new
/// sign-in, starting fresh, "나중에 넣을게요" or a new guest account. The core keeps the other account's events apart and
/// backs nothing up until it has the answer (`settleLocalData`); a restore takes the answer itself
/// (`RestoreModel.choose`).
@MainActor
@Observable
public final class LocalDataQuestion {
    /// What the sheet counts; `nil` while there is nothing to ask.
    public private(set) var asking: LocalData?
    public private(set) var isWorking = false

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let notify: @MainActor (String) -> Void
    @ObservationIgnored private var next: (@MainActor () -> Void)?

    public init(core: any CoreProtocol, notify: @escaping @MainActor (String) -> Void) {
        self.core = core
        self.notify = notify
    }

    /// Asks when another account's events are on the device, and goes on to `next` at once otherwise. The sheet only
    /// closes with an answer, so `next` waits for it. Without a session there is nothing to ask yet: the core keeps the
    /// store's owner after a deletion or a sign-out but takes an answer only for a signed-in account, so the question
    /// waits until the next account is registered.
    public func ask(then next: @escaping @MainActor () -> Void) async {
        guard asking == nil, !isWorking else { return }
        isWorking = true
        let core = self.core
        let local = await Task.detached {
            core.session() == nil ? nil : ((try? core.localDataOfOtherAccount()) ?? nil)
        }.value
        isWorking = false
        guard let local else {
            next()
            return
        }
        self.next = next
        asking = local
    }

    /// The person's answer, taken once. A failure keeps the sheet, since the core still waits for an answer; a session
    /// that ended meanwhile withdraws the question instead, as the sheet only closes with an answer the core can no
    /// longer take.
    public func answer(_ choice: LocalDataChoice) async {
        guard asking != nil, !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        let core = self.core
        do {
            try await Task.detached { try core.settleLocalData(choice: choice) }.value
        } catch CoreError.NotSignedIn {
            withdraw()
            return
        } catch {
            notify(Strings.somethingFailed)
            return
        }
        asking = nil
        let next = self.next
        self.next = nil
        next?()
    }

    /// The session ended while the question was up (a sign-out from another device, a refused refresh token): the core
    /// takes an answer only from a signed-in account, so the sheet goes and the next sign-in asks again. What waited for
    /// the answer does not go on.
    public func withdraw() {
        asking = nil
        next = nil
    }
}
