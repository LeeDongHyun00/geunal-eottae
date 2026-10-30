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
        case failed
    }

    public var typed = ""
    public private(set) var isWorking = false
    /// "준비하는 중…": the core is uploading a guest's recovery material before it deletes again.
    public private(set) var preparing = false
    public private(set) var problem: Problem?
    public private(set) var deleted = false
    /// `false` on a device without a passcode: the OS asks nothing, and the sheet recommends a screen lock.
    public let screenLocked: Bool

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let providers: any ProviderSignIn
    @ObservationIgnored private let owner: any DeviceOwnerCheck
    @ObservationIgnored private let account: AccountView?
    /// The sheet asks the OS once; "카카오로 다시 확인" after a failed check does not ask again (m1.md §4 "기기 인증").
    @ObservationIgnored private var ownerConfirmed = false

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
        typed.trimmingCharacters(in: .whitespacesAndNewlines) == Self.word && !isWorking
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
        if !ownerConfirmed {
            guard await owner.confirm(.deleteAccount) else { return false }
            ownerConfirmed = true
        }
        problem = nil
        do {
            try await delete(with: try await proof())
            deleted = true
            return true
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

    /// 409 `recovery_missing` for a guest: the core uploads the recovery material on a sync, and the deletion goes
    /// once more with the same proof, which the device signs again (m1.md screen 22 "오류").
    private func delete(with proof: DeletionProof) async throws {
        do {
            try await core.deleteAccount(proof: proof)
        } catch CoreError.RecoveryMissing where proof == .guest {
            preparing = true
            try await core.sync()
            try await core.deleteAccount(proof: proof)
        }
    }

    private func proof() async throws -> DeletionProof {
        switch provider {
        case .kakao:
            let nonce = try await core.signInNonce()
            let credential = try await providers.kakao(providerNonce: nonce.providerNonce)
            return .kakao(nonce: nonce.nonce, idToken: credential.idToken)
        case .apple:
            let nonce = try await core.signInNonce()
            let credential = try await providers.apple(providerNonce: nonce.providerNonce)
            guard let code = credential.authorizationCode else { throw ProviderSignInError.unavailable }
            return .apple(authorizationCode: code)
        case nil:
            return .guest
        }
    }
}
