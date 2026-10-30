import Foundation
import GeunalCore
import Observation

/// 계정 삭제 (m1.md screen 22): what the server deletes and what stays on this device, then the sheet that asks for
/// "삭제" typed out. The OS prompt comes first (decision 26). Then the server wants the account proven at that moment
/// (protocol §13): a guest's proof comes from the recovery key on the device, a Kakao or Apple account's from a fresh
/// sign-in with that provider.
@MainActor
@Observable
public final class DeleteAccountModel {
    public enum Problem: Equatable, Sendable {
        case offline
        /// 403 `reauth_required`: the provider sign-in was another Kakao or Apple account, or brought no proof.
        /// Nothing was deleted and the session is still good, so the sheet asks for the linked account again.
        case reauthRequired
        /// The guest's recovery material still is not on the server after a sync, so the guest cannot prove the account.
        case recoveryMissing
        /// The connection broke before the answer came (m1.md screen 22, decision 36): the server may already have
        /// deleted the account. The sheet asks the core once there is a connection.
        case answerLost
        /// The check found the account still there: pressing again sends the first proof once more.
        case notDeleted
        case failed
    }

    public var typed = ""
    public private(set) var isWorking = false
    /// "준비하는 중…": the core is uploading a guest's recovery material before it deletes again.
    public private(set) var preparing = false
    public private(set) var problem: Problem?
    /// "확인하는 중…": a sync is asking whether a deletion whose answer was lost went through.
    public private(set) var checking = false
    public private(set) var deleted = false
    /// `false` on a device without a passcode: the OS asks nothing, and the sheet recommends a screen lock.
    public let screenLocked: Bool

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let providers: any ProviderSignIn
    @ObservationIgnored private let owner: any DeviceOwnerCheck
    @ObservationIgnored private let account: AccountView?
    /// The sheet asks the OS once; "카카오로 다시 확인" after a failed check does not ask again (m1.md §4 "기기 인증").
    @ObservationIgnored private var ownerConfirmed = false
    /// The proof of a deletion whose answer was lost, sent again before anything is asked anew (decision 36). In
    /// memory only, and only while the sheet is open (docs/security/app-review.md §4).
    @ObservationIgnored private var sentProof: DeletionProof?

    public init(
        core: any CoreProtocol, providers: any ProviderSignIn, owner: any DeviceOwnerCheck, account: AccountView?
    ) {
        self.core = core
        self.providers = providers
        self.owner = owner
        self.account = account
        screenLocked = owner.hasScreenLock
    }

    /// The word the sheet asks for, in the interface's own language.
    public static var word: String { String(localized: "삭제", bundle: .module) }

    public var canConfirm: Bool {
        typed.trimmingCharacters(in: .whitespacesAndNewlines) == Self.word && !isWorking && problem != .answerLost
    }

    /// "하린" for "하린님의 계정을 삭제할까요?".
    public var displayName: String { account?.displayName ?? "" }

    /// The provider the sheet re-checks with, `nil` for a guest. Kakao first when both are linked.
    public var provider: Provider? {
        let linked = account?.providers ?? []
        if linked.contains(.kakao) { return .kakao }
        if linked.contains(.apple) { return .apple }
        return nil
    }

    /// Asks the OS, proves the account, deletes it on the server, then the core forgets the secrets; the calendar
    /// stays. Cancelling the OS prompt or the provider's sign-in leaves the sheet as it was, "삭제" still typed.
    public func delete(online: Bool) async -> Bool {
        guard canConfirm else { return false }
        guard online else {
            problem = .offline
            return false
        }
        isWorking = true
        defer {
            isWorking = false
            preparing = false
        }
        if let proof = sentProof {
            // The account was still there after a lost answer: the first proof goes again before anything is asked
            // anew (decision 36). A proof the server already took answers reauth_required, and only then the sheet
            // asks again from the OS prompt on.
            sentProof = nil
            problem = nil
            do {
                try await send(proof)
                deleted = true
                return true
            } catch is AnswerLost {
                sentProof = proof
                problem = .answerLost
                return false
            } catch CoreError.ReauthRequired {
                ownerConfirmed = false
            } catch {
                problem = .failed
                return false
            }
        }
        if !ownerConfirmed {
            guard await owner.confirm(.deleteAccount) else { return false }
            ownerConfirmed = true
        }
        problem = nil
        var proof: DeletionProof?
        do {
            let made = try await self.proof()
            proof = made
            try await send(made)
            deleted = true
            return true
        } catch is AnswerLost {
            sentProof = proof
            problem = .answerLost
        } catch ProviderSignInError.cancelled {
            // Nothing was deleted and nothing needs saying.
        } catch CoreError.ReauthRequired {
            problem = provider == nil ? .failed : .reauthRequired
        } catch CoreError.RecoveryMissing {
            problem = .recoveryMissing
        } catch CoreError.Network(_) {
            problem = .offline
        } catch {
            problem = .failed
        }
        return false
    }

    /// After a lost answer, once there is a connection: a sync with this session always reaches the server, so it
    /// either finds the account gone (401 `account_deleted`, and the core has finished as after a 204) or still there.
    /// Any other failure leaves the question to the next connection (m1.md screen 22, decision 36).
    public func checkLostAnswer() async {
        guard problem == .answerLost, !checking else { return }
        checking = true
        defer { checking = false }
        do {
            try await core.sync()
            problem = .notDeleted
        } catch let error as CoreError where error.isAccountDeleted {
            deleted = true
        } catch {
            // Still unknown.
        }
    }

    /// The sheet closed: its proof goes (docs/security/app-review.md §4). A deletion left without its answer stays
    /// unknown until a call reaches the server; the next press proves the account afresh.
    public func sheetClosed() {
        sentProof = nil
        if problem == .answerLost || problem == .notDeleted {
            problem = nil
        }
    }

    /// The deletion went out, and the connection broke before its answer.
    private struct AnswerLost: Error {}

    /// 409 `recovery_missing` for a guest: the core uploads the recovery material on a sync, and the deletion goes
    /// once more with the same proof, which the device signs again (m1.md screen 22 "오류").
    private func send(_ proof: DeletionProof) async throws {
        do {
            try await deleteOnce(proof)
        } catch CoreError.RecoveryMissing where proof == .guest {
            preparing = true
            try await core.sync()
            try await deleteOnce(proof)
        }
    }

    /// 401 `account_deleted` means the account is gone, deleted by this call or by one whose answer was lost, and the
    /// core has finished as after a 204 (core #71).
    private func deleteOnce(_ proof: DeletionProof) async throws {
        do {
            try await core.deleteAccount(proof: proof)
        } catch let error as CoreError where error.isAccountDeleted {
            return
        } catch CoreError.Network(_) {
            throw AnswerLost()
        }
    }

    private func proof() async throws -> DeletionProof {
        guard let provider else { return .guest }
        return try await ProviderProof.make(with: provider, core: core, providers: providers)
    }
}

extension CoreError {
    /// 401 `account_deleted` (protocol §13 "답을 잃은 삭제"): the account is gone, and the core has already finished as
    /// after a 204 (core #71). Any call that reaches the server can be the one that learns it.
    public var isAccountDeleted: Bool {
        guard case .Api(let status, let code, _) = self else { return false }
        return status == 401 && code == "account_deleted"
    }
}
