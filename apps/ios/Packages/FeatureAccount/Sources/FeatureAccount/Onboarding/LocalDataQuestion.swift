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
    /// closes with an answer, so `next` waits for it.
    public func ask(then next: @escaping @MainActor () -> Void) async {
        guard asking == nil, !isWorking else { return }
        isWorking = true
        let core = self.core
        let local = await Task.detached { (try? core.localDataOfOtherAccount()) ?? nil }.value
        isWorking = false
        guard let local else {
            next()
            return
        }
        self.next = next
        asking = local
    }

    /// The person's answer, taken once. A failure keeps the sheet, since the core still waits for an answer.
    public func answer(_ choice: LocalDataChoice) async {
        guard asking != nil, !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        let core = self.core
        do {
            try await Task.detached { try core.settleLocalData(choice: choice) }.value
        } catch {
            notify(Strings.somethingFailed)
            return
        }
        asking = nil
        let next = self.next
        self.next = nil
        next?()
    }
}
