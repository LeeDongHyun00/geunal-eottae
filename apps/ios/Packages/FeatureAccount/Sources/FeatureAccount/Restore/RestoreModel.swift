import Foundation
import GeunalCore
import Observation

/// Entering the twelve-word recovery code (m1.md screen 21) and restoring with it, for the three ways a device gets
/// here: a second device of a Kakao or Apple account (`deviceChange`), an account whose backup the server holds
/// (`offer`), and a guest coming back on a new device (`guestReturn`).
@MainActor
@Observable
public final class RestoreModel {
    public enum Mode: Hashable, Sendable {
        case deviceChange
        case offer
        case guestReturn
    }

    public enum Problem: Equatable, Sendable {
        /// The word in this cell (0-based) is not one of the 2048. Judged on the device.
        case unknownWord(index: Int)
        /// The code did not open this account's key, or found no guest account. The core does not tell a bad checksum
        /// apart from a code that does not unwrap yet, so both land here.
        case wrongCode
        case offline
        case failed
    }

    public enum Phase: Equatable, Sendable {
        case entering
        /// Another account's events are on this device; ask before restoring (m1.md screen 24).
        case askingLocalData(LocalData)
        case restoring(done: UInt64, total: UInt64)
        case finished(RestoreSummary)
        /// "백업 없이 새로 시작" or a new guest account: onboarding goes on without a restore.
        case startedFresh
        /// The replacement ticket ran out (30 minutes); the person signs in again.
        case ticketExpired
    }

    public static let wordCount = 12

    public let mode: Mode
    /// What each cell shows. The view binds to it and calls `edited(_:)` on every change.
    public var cells: [String] = Array(repeating: "", count: RestoreModel.wordCount)
    /// The cell with the keyboard; a cell turns red only once the person leaves it.
    public var focused: Int? = 0
    public private(set) var accepted: [String?] = Array(repeating: nil, count: RestoreModel.wordCount)
    /// The one word a cell's prefix leads to, shown as a hint and taken when the person leaves the cell.
    public private(set) var completions: [String?] = Array(repeating: nil, count: RestoreModel.wordCount)
    public private(set) var problem: Problem?
    public private(set) var phase: Phase = .entering
    public private(set) var isWorking = false

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let ageConfirmedAt: Date?
    @ObservationIgnored private let notify: @MainActor (String) -> Void

    /// `ageConfirmedAt` is the sign-in screen's tick, which a new guest account needs; without it "새 게스트 계정으로
    /// 시작" sends the person back to tick the box.
    public init(
        mode: Mode, core: any CoreProtocol, ageConfirmedAt: Date? = nil, notify: @escaping @MainActor (String) -> Void
    ) {
        self.mode = mode
        self.core = core
        self.ageConfirmedAt = ageConfirmedAt
        self.notify = notify
    }

    public var acceptedCount: Int { accepted.compactMap { $0 }.count }

    /// The first cell the person left with a word that is not in the list.
    public var firstUnknownIndex: Int? {
        (0..<Self.wordCount).first { index in
            index != focused && !cells[index].trimmed.isEmpty && accepted[index] == nil
        }
    }

    public var isComplete: Bool { acceptedCount == Self.wordCount }

    public func isUnknown(_ index: Int) -> Bool {
        index != focused && !cells[index].trimmed.isEmpty && accepted[index] == nil
    }

    /// Checks the cell's text against the word list. A full word moves the keyboard on at once, if the cell still has
    /// it: a completion taken after the person tapped another cell must not pull them back. A shorter prefix that
    /// leads to one word only becomes a hint: Hangul composition passes through syllables that are prefixes of other
    /// words ("가이" on the way to "가입" leads to "가이드"), so replacing the text while the person types would pick
    /// the wrong word for 20 of the 2048.
    public func edited(_ index: Int) async {
        let text = cells[index].trimmed
        let core = self.core
        let (word, matches) = await Task.detached { () -> (String?, [String]) in
            guard !text.isEmpty else { return (nil, []) }
            let word = (try? core.checkRecoveryWord(text: text)) ?? nil
            let matches = word == nil ? ((try? core.recoveryCompletions(prefix: text)) ?? []) : []
            return (word, matches)
        }.value
        // Typing may have gone on while the core looked.
        guard cells[index].trimmed == text else { return }
        accepted[index] = word
        completions[index] = matches.count == 1 ? matches[0] : nil
        if word != nil {
            if problem == .wrongCode { problem = nil }
            if focused == index {
                moveFocus(after: index)
            }
        }
        refreshProblem()
    }

    /// The person left a cell, for another one or by putting the keyboard away: take its single completion, if any.
    public func committed(_ index: Int) async {
        if accepted[index] == nil, let completion = completions[index] {
            cells[index] = completion
            await edited(index)
        }
        refreshProblem()
    }

    /// The return key ("다음"): leave the cell, then go on to the next empty one. SwiftUI may have dropped the focus
    /// before this runs, so a focus of `nil` still moves on; a cell the person tapped in the meantime keeps it.
    public func submitted(_ index: Int) async {
        await committed(index)
        if focused == nil || focused == index {
            moveFocus(after: index)
        }
    }

    /// "붙여넣기": a whole code in one go, numbering and line breaks dropped by the core.
    public func paste(_ text: String) async {
        let core = self.core
        let words = await Task.detached { (try? core.splitRecoveryCode(text: text)) ?? [] }.value
        guard !words.isEmpty else { return }
        for (index, word) in words.prefix(Self.wordCount).enumerated() {
            cells[index] = word
        }
        focused = nil
        for index in 0..<min(words.count, Self.wordCount) {
            await edited(index)
        }
        focused = cells.firstIndex { $0.trimmed.isEmpty }
        refreshProblem()
    }

    /// The main button: asks about another account's events first when there are any, then restores. Busy from the
    /// first suspension, so a second tap while the core looks cannot start a second restore.
    public func start() async {
        guard isComplete, !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        let core = self.core
        if let local = await Task.detached(operation: { (try? core.localDataOfOtherAccount()) ?? nil }).value {
            phase = .askingLocalData(local)
            return
        }
        await restore(localData: .keep)
    }

    /// The answer on screen 24, taken once.
    public func choose(_ choice: LocalDataChoice) async {
        guard case .askingLocalData = phase, !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        await restore(localData: choice)
    }

    /// The "복구 코드가 없어요" sheet's red button. A guest without the tick goes back to the sign-in screen.
    public func startWithoutCode() async -> Bool {
        guard !isWorking else { return false }
        isWorking = true
        defer { isWorking = false }
        do {
            switch mode {
            case .deviceChange, .offer:
                _ = try await core.startFresh(proof: nil)
            case .guestReturn:
                guard let ageConfirmedAt else { return false }
                _ = try await core.signInGuest(
                    account: NewAccount(displayName: SignInModel.guestName, ageConfirmedAt: ageConfirmedAt))
            }
            phase = .startedFresh
            return true
        } catch CoreError.Network(_) {
            problem = .offline
        } catch {
            notify(Strings.somethingFailed)
        }
        return false
    }

    /// Callers hold `isWorking` around it.
    private func restore(localData: LocalDataChoice) async {
        problem = nil
        phase = .restoring(done: 0, total: 0)
        let words = accepted.compactMap { $0 }
        let relay = ProgressRelay { [weak self] done, total in
            Task { @MainActor in
                self?.progressed(done: done, total: total)
            }
        }
        do {
            let summary = try await core.restoreFromCode(words: words, localData: localData, progress: relay)
            phase = .finished(summary)
        } catch CoreError.WrongRecoveryCode {
            problem = .wrongCode
            phase = .entering
        } catch CoreError.Network(_) {
            problem = .offline
            phase = .entering
        } catch CoreError.SignInAgain {
            notify(Strings.ticketExpired)
            phase = .ticketExpired
        } catch {
            problem = .failed
            phase = .entering
        }
    }

    private func progressed(done: UInt64, total: UInt64) {
        if case .restoring = phase {
            phase = .restoring(done: done, total: total)
        }
    }

    private func moveFocus(after index: Int) {
        let next =
            ((index + 1)..<Self.wordCount).first { accepted[$0] == nil }
            ?? (0..<index).first { accepted[$0] == nil }
        focused = next
    }

    private func refreshProblem() {
        if let index = firstUnknownIndex {
            problem = .unknownWord(index: index)
        } else if case .unknownWord = problem {
            problem = nil
        }
    }
}

/// Hands the core's progress callbacks, which come on a core thread, to the screen.
final class ProgressRelay: ProgressListener {
    private let handler: @Sendable (UInt64, UInt64) -> Void

    init(_ handler: @escaping @Sendable (UInt64, UInt64) -> Void) {
        self.handler = handler
    }

    func onProgress(done: UInt64, total: UInt64) {
        handler(done, total)
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
