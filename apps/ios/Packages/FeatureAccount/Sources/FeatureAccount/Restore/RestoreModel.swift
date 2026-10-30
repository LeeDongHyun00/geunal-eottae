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
        /// All twelve words are in the list but their checksum does not match: one word off, or two swapped. Judged on
        /// the device, before anything goes to the server.
        case checksum
        /// The code did not open this account's key, or found no guest account. One wrong code in sixteen passes the
        /// checksum and lands here.
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
        /// "나중에 넣을게요" on the offer: the calendar works on the device, and backups wait for the code.
        case later
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
    /// `false` on a device without a passcode: "백업 없이 새로 시작" goes without the OS prompt, and the sheet
    /// recommends a screen lock.
    public let screenLocked: Bool

    /// What can go wrong on the "백업 없이 새로 시작" confirmation, shown in its sheet.
    public enum FreshProblem: Equatable, Sendable {
        case offline
        /// 403 `reauth_required` after a provider check: the person signed in with a Kakao or Apple account that is not
        /// this one's. Nothing was deleted.
        case otherAccount
        case failed
    }

    /// The backups the confirmation deletes, newest first; `nil` until read, or when they could not be read.
    public private(set) var backupsToDelete: [BackupView]?
    /// Starting fresh needs the provider's proof (m1.md decision 25): on the offer more than 30 minutes after the last
    /// Kakao or Apple sign-in, or once the server said so. A replacement ticket lasts 30 minutes, so the device change
    /// never needs it.
    public private(set) var needsProof: Bool
    /// The provider of that proof: the one this device last signed in with.
    public let proofProvider: Provider
    public private(set) var freshProblem: FreshProblem?

    /// How long after a Kakao or Apple sign-in starting fresh goes without the provider's proof (protocol §10).
    static let proofWindow: TimeInterval = 30 * 60

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let owner: any DeviceOwnerCheck
    @ObservationIgnored private let providers: (any ProviderSignIn)?
    @ObservationIgnored private let ageConfirmedAt: Date?
    @ObservationIgnored private let notify: @MainActor (String) -> Void
    /// The confirmation asks the OS once while its sheet is up; "카카오로 다시 확인" does not ask again (decision 26).
    @ObservationIgnored private var ownerConfirmed = false

    /// `ageConfirmedAt` is the sign-in screen's tick, which a new guest account needs; without it "새 게스트 계정으로
    /// 시작" sends the person back to tick the box. `lastSignIn` is this device's last Kakao or Apple sign-in, which
    /// decides whether starting fresh on the offer needs the provider's proof.
    public init(
        mode: Mode, core: any CoreProtocol, owner: any DeviceOwnerCheck, providers: (any ProviderSignIn)? = nil,
        lastSignIn: (provider: Provider, at: Date)? = nil, ageConfirmedAt: Date? = nil,
        now: @Sendable () -> Date = Date.init, notify: @escaping @MainActor (String) -> Void
    ) {
        self.mode = mode
        self.core = core
        self.owner = owner
        self.providers = providers
        self.ageConfirmedAt = ageConfirmedAt
        self.notify = notify
        screenLocked = owner.hasScreenLock
        proofProvider = lastSignIn?.provider ?? .kakao
        needsProof =
            mode == .offer
            && (lastSignIn.map { now().timeIntervalSince($0.at) >= Self.proofWindow } ?? true)
    }

    public var acceptedCount: Int { accepted.compactMap { $0 }.count }

    /// The first cell the person left with a word that is not in the list.
    public var firstUnknownIndex: Int? {
        (0..<Self.wordCount).first { index in
            index != focused && !cells[index].trimmed.isEmpty && accepted[index] == nil
        }
    }

    public var isComplete: Bool { acceptedCount == Self.wordCount }

    /// A restore under way or just done: nothing may take the person away from it (m1.md decision 30).
    public var holdsTheScreen: Bool {
        switch phase {
        case .restoring, .finished: true
        default: false
        }
    }

    /// Twelve words in the list whose checksum matches: the main button can try them.
    public var canStart: Bool { isComplete && problem != .checksum && !isWorking }

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
        await checkCode()
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
        await checkCode()
    }

    /// The main button: asks about another account's events first when there are any, then restores. Busy from the
    /// first suspension, so a second tap while the core looks cannot start a second restore.
    public func start() async {
        guard canStart else { return }
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

    /// The confirmation's count and last time (`GET /me/backups`, which a replacement ticket reads too). Without them the
    /// confirmation still deletes, it only says less.
    public func loadBackups() async {
        backupsToDelete = try? await core.backups()
    }

    /// "나중에 넣을게요 · 캘린더부터 쓰기", the offer only (m1.md decision 24).
    public func later() {
        guard mode == .offer, !isWorking else { return }
        phase = .later
    }

    /// The no-code sheet went away: the next confirmation asks the OS again, without the last one's problem.
    public func noCodeClosed() {
        ownerConfirmed = false
        freshProblem = nil
    }

    /// The confirmation's red button, or the guest's "새 게스트 계정으로 시작". A guest without the tick goes back to the
    /// sign-in screen.
    public func startWithoutCode() async -> Bool {
        guard !isWorking else { return false }
        isWorking = true
        defer { isWorking = false }
        var sentProof = false
        do {
            switch mode {
            case .deviceChange, .offer:
                // The account's backups go for good, so the OS asks first (decision 26); cancelling keeps the sheet.
                if !ownerConfirmed {
                    guard await owner.confirm(.startFresh) else { return false }
                    ownerConfirmed = true
                }
                freshProblem = nil
                var proof: DeletionProof?
                if needsProof {
                    guard let providers else { throw ProviderSignInError.unavailable }
                    proof = try await ProviderProof.make(with: proofProvider, core: core, providers: providers)
                    sentProof = true
                }
                _ = try await core.startFresh(proof: proof)
            case .guestReturn:
                guard let ageConfirmedAt else { return false }
                _ = try await core.signInGuest(
                    account: NewAccount(displayName: SignInModel.guestName, ageConfirmedAt: ageConfirmedAt))
            }
            phase = .startedFresh
            return true
        } catch ProviderSignInError.cancelled {
            // The provider's sheet closed: nothing was deleted, and the confirmation stays as it was.
        } catch CoreError.ReauthRequired {
            // Sent without a proof, the 30 minutes ran out meanwhile: nothing was deleted, so the button only turns
            // into the provider check. With a proof, it was another Kakao or Apple account's (m1.md decision 25).
            if sentProof {
                freshProblem = .otherAccount
            } else {
                needsProof = true
            }
        } catch CoreError.SignInAgain {
            notify(Strings.ticketExpired)
            phase = .ticketExpired
        } catch CoreError.Network(_) {
            if mode == .guestReturn {
                problem = .offline
            } else {
                freshProblem = .offline
            }
        } catch {
            if mode == .guestReturn {
                notify(Strings.somethingFailed)
            } else {
                freshProblem = .failed
            }
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

    /// The checksum, once every word is in the list: the device judges it without the server (m1.md screen 21).
    private func checkCode() async {
        guard isComplete else {
            if problem == .checksum { problem = nil }
            return
        }
        let words = accepted.compactMap { $0 }
        let core = self.core
        let check = await Task.detached { core.checkRecoveryCode(words: words) }.value
        // A word may have changed while the core looked.
        guard accepted.compactMap({ $0 }) == words else { return }
        if check == .checksum {
            problem = .checksum
        } else if problem == .checksum {
            problem = nil
        }
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
