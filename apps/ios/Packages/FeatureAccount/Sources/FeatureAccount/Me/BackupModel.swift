import Foundation
import GeunalCore
import Observation

/// 암호화 백업 (m1.md screen 20): the last backup, "지금 백업" with its progress, the automatic switch and the three
/// backups the server keeps. A backup is sealed on the device with the account key; only the recovery code opens it.
@MainActor
@Observable
public final class BackupModel {
    public enum Phase: Equatable, Sendable {
        case idle
        case running(done: UInt64, total: UInt64)
        /// The upload failed for the network: the automatic backup tries again (m1.md screen 20 "실패").
        case waitingForNetwork
        case failed
    }

    public private(set) var status: BackupStatus?
    /// "종이에 적었어요" was pressed; until then the screen says a backup only comes back with the code.
    public private(set) var recoverySaved = true
    public private(set) var backups: [BackupView] = []
    public private(set) var phase: Phase = .idle
    /// The backup this screen just made, for "방금".
    public private(set) var justMade = false

    @ObservationIgnored private let core: any CoreProtocol

    public init(core: any CoreProtocol) {
        self.core = core
    }

    public var isRunning: Bool {
        if case .running = phase { return true }
        return false
    }

    /// Backups wait for the recovery material on the server; until then the button stays off.
    public var canBackUp: Bool { status?.ready == true && !isRunning }

    public func load() async {
        let core = self.core
        status = try? await offMain { try core.backupStatus() }
        recoverySaved = (try? await offMain { try core.recoveryStatus() })?.saved ?? true
        // The list comes from the server; offline it stays as it was.
        if let list = try? await core.backups() {
            backups = list
        }
    }

    public func backUpNow() async {
        guard canBackUp else { return }
        phase = .running(done: 0, total: 0)
        justMade = false
        let relay = ProgressRelay { [weak self] done, total in
            Task { @MainActor in
                self?.progressed(done: done, total: total)
            }
        }
        do {
            _ = try await core.backupNow(progress: relay)
            phase = .idle
            justMade = true
        } catch CoreError.Network(_) {
            phase = .waitingForNetwork
        } catch {
            phase = .failed
        }
        await load()
    }

    public func setAuto(_ on: Bool) async {
        let core = self.core
        do {
            try await offMain { try core.setAutoBackup(enabled: on) }
        } catch {
            phase = .failed
        }
        status = try? await offMain { try core.backupStatus() }
    }

    private func progressed(done: UInt64, total: UInt64) {
        if case .running = phase {
            phase = .running(done: done, total: total)
        }
    }
}
