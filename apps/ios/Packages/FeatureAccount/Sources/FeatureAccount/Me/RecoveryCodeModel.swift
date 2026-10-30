import Foundation
import GeunalCore
import Observation

/// 복구 코드 (m1.md screen 13): the twelve words, the warning, the backup line, "복사" and "종이에 적었어요". The words
/// are read from the core each time the screen opens and never kept by the app.
@MainActor
@Observable
public final class RecoveryCodeModel {
    public enum Phase: Equatable, Sendable {
        case loading
        case shown
        /// No code on this device yet: made at the first sign-in that finds none on the server (protocol §10).
        case none
    }

    public private(set) var phase: Phase = .loading
    public private(set) var words: [String] = []
    public private(set) var saved = false
    public private(set) var lastBackupAt: Date?
    public private(set) var failed = false

    @ObservationIgnored private let core: any CoreProtocol

    public init(core: any CoreProtocol) {
        self.core = core
    }

    public func load() async {
        let core = self.core
        let read = try? await offMain { (try core.recoveryWords(), try core.recoveryStatus(), try core.meSummary()) }
        guard let read, read.0.count == 12 else {
            phase = .none
            return
        }
        words = read.0
        saved = read.1.saved
        lastBackupAt = read.2.lastBackupAt
        phase = .shown
    }

    /// "1. 바람 2. 연필 …": the restore screen's paste takes it back with the numbers (`splitRecoveryCode`).
    public var copyText: String {
        words.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: " ")
    }

    /// "종이에 적었어요": the banner and the red "저장 안 함" go away.
    public func markSaved() async -> Bool {
        let core = self.core
        do {
            try await offMain { try core.markRecoverySaved() }
            saved = true
            failed = false
            return true
        } catch {
            failed = true
            return false
        }
    }
}
